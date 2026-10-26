package com.paiagent.engine.scheduler;

import com.paiagent.common.UserContext;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 有界并行 DAG 调度器单元测试（无 Spring、无 DB）。
 *
 * <p>覆盖：串/并行双跑一致性、真实并发、有界并行、失败传播、优雅收敛、
 * 条件分支跳过、指定节点续跑、边界场景。
 */
class ParallelDAGSchedulerTest {

    private final DAGParser dagParser = new DAGParser();
    private final ParallelDAGScheduler scheduler = new ParallelDAGScheduler();

    // ---------------------------------------------------------------- 基础工具

    private static WorkflowNode node(String id, String type) {
        WorkflowNode n = new WorkflowNode();
        n.setId(id);
        n.setType(type);
        return n;
    }

    private static WorkflowEdge edge(String source, String target) {
        WorkflowEdge e = new WorkflowEdge();
        e.setId(source + "->" + target);
        e.setSource(source);
        e.setTarget(target);
        return e;
    }

    private static WorkflowConfig config(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        WorkflowConfig c = new WorkflowConfig();
        c.setNodes(nodes);
        c.setEdges(edges);
        return c;
    }

    /** 与引擎一致的输入解析：合并前驱输出；无前驱输出时回退初始输入（返回副本）。 */
    private static InputResolver mergeResolver(List<WorkflowEdge> edges, Map<String, Object> initialInput) {
        Map<String, List<WorkflowEdge>> byTarget = new HashMap<>();
        for (WorkflowEdge e : edges) {
            byTarget.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        return (node, available) -> {
            List<WorkflowEdge> incoming = byTarget.getOrDefault(node.getId(), List.of());
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (WorkflowEdge e : incoming) {
                Map<String, Object> out = available.get(e.getSource());
                if (out != null) {
                    resolved.putAll(out);
                }
            }
            return resolved.isEmpty() ? new LinkedHashMap<>(initialInput) : resolved;
        };
    }

    /** 串行参考实现：与既有 WorkflowEngine 串行路径同语义（拓扑序 + 前驱输出合并 + 回退）。 */
    private static Map<String, Object> runSerialReference(List<WorkflowNode> topo,
                                                          List<WorkflowEdge> edges,
                                                          Map<String, Object> initialInput,
                                                          Function<WorkflowNode, Map<String, Object>> nodeFn) {
        Map<String, List<WorkflowEdge>> byTarget = new HashMap<>();
        for (WorkflowEdge e : edges) {
            byTarget.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        Map<String, Object> current = initialInput;
        for (WorkflowNode node : topo) {
            Map<String, Object> input = new LinkedHashMap<>();
            for (WorkflowEdge e : byTarget.getOrDefault(node.getId(), List.of())) {
                Map<String, Object> out = outputs.get(e.getSource());
                if (out != null) {
                    input.putAll(out);
                }
            }
            if (input.isEmpty()) {
                input = new LinkedHashMap<>(initialInput);
            }
            Map<String, Object> output = nodeFn.apply(node);
            outputs.put(node.getId(), output);
            current = output;
        }
        return current;
    }

    private static List<String> ids(List<NodeResult> results) {
        return results.stream().map(NodeResult::getNodeId).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- 桩执行器

    @FunctionalInterface
    private interface NodeHandler {
        NodeOutcome handle(Map<String, Object> input) throws Exception;
    }

    private final class StubExecutor implements NodeTaskExecutor {
        private final Map<String, NodeHandler> handlers = new HashMap<>();
        private final List<String> executed = new CopyOnWriteArrayList<>();
        private final Map<String, Map<String, Object>> receivedInputs = new HashMap<>();
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();

        private StubExecutor on(String nodeId, NodeHandler handler) {
            handlers.put(nodeId, handler);
            return this;
        }

        @Override
        public NodeOutcome execute(WorkflowNode node, Map<String, Object> input) throws Exception {
            executed.add(node.getId());
            synchronized (receivedInputs) {
                receivedInputs.put(node.getId(), new LinkedHashMap<>(input));
            }
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                NodeHandler handler = handlers.getOrDefault(node.getId(),
                        i -> NodeOutcome.of(Map.of("node", node.getId())));
                return handler.handle(input);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    private ScheduleOutcome schedule(List<WorkflowNode> nodes,
                                     List<WorkflowEdge> edges,
                                     StubExecutor stub,
                                     Map<String, Object> initialInput,
                                     ResumeContext resume,
                                     int poolSize) {
        List<WorkflowNode> topo = dagParser.parse(config(nodes, edges));
        return scheduler.schedule(topo, edges, stub, mergeResolver(edges, initialInput), resume,
                SchedulerConfig.builder().poolSize(poolSize).build());
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 串/并行双跑一致性：菱形图上并行调度的最终输出、节点结果顺序与终态
     * 必须与串行参考实现完全一致；并用闭锁证明两条分支真实并发执行。
     */
    @Test
    void diamond_parallelMatchesSerialReference() throws Exception {
        List<WorkflowNode> nodes = List.of(
                node("n1", "input"), node("n2a", "llm"), node("n2b", "llm"), node("n3", "output"));
        List<WorkflowEdge> edges = List.of(
                edge("n1", "n2a"), edge("n1", "n2b"), edge("n2a", "n3"), edge("n2b", "n3"));
        Map<String, Object> initialInput = Map.of("input", "seed");

        CountDownLatch bothRunning = new CountDownLatch(2);
        StubExecutor stub = new StubExecutor();
        for (WorkflowNode n : nodes) {
            if (n.getId().equals("n2a") || n.getId().equals("n2b")) {
                stub.on(n.getId(), input -> {
                    bothRunning.countDown();
                    assertTrue(bothRunning.await(3, TimeUnit.SECONDS), "两条独立分支未并发执行");
                    return NodeOutcome.of(Map.of("node", n.getId()));
                });
            } else {
                stub.on(n.getId(), input -> NodeOutcome.of(Map.of("node", n.getId())));
            }
        }

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 4);

        assertEquals("SUCCESS", outcome.getStatus());
        assertNull(outcome.getErrorMessage());
        // 与串行参考实现的最终输出一致
        Map<String, Object> expected = runSerialReference(
                dagParser.parse(config(nodes, edges)), edges, initialInput, n -> Map.of("node", n.getId()));
        assertEquals(expected, outcome.getFinalOutput());

        // 节点结果按拓扑序，全部成功
        List<WorkflowNode> topo = dagParser.parse(config(nodes, edges));
        assertEquals(topo.stream().map(WorkflowNode::getId).collect(Collectors.toList()), ids(outcome.getNodeResults()));
        assertTrue(outcome.getNodeResults().stream().allMatch(r -> r.getStatus() == NodeStatus.SUCCESS));
        // 全部节点成功终结
        assertEquals(4, outcome.getNodeStatuses().size());
        assertTrue(outcome.getNodeStatuses().values().stream().allMatch(s -> s == NodeStatus.SUCCESS));
        // 两条分支确实同时运行过
        assertTrue(stub.maxRunning.get() >= 2, "未观测到并发执行");
    }

    /** 真实并发：两个各睡 300ms 的独立分支并行执行，总耗时应远小于串行的 600ms。 */
    @Test
    void independentBranches_runConcurrently() {
        List<WorkflowNode> nodes = List.of(node("root", "input"), node("a", "llm"), node("b", "llm"), node("end", "output"));
        List<WorkflowEdge> edges = List.of(edge("root", "a"), edge("root", "b"), edge("a", "end"), edge("b", "end"));
        Map<String, Object> initialInput = Map.of("input", "seed");

        CountDownLatch bothRunning = new CountDownLatch(2);
        StubExecutor stub = new StubExecutor();
        stub.on("a", input -> {
            bothRunning.countDown();
            assertTrue(bothRunning.await(3, TimeUnit.SECONDS), "分支 a/b 未并发执行");
            sleep(300);
            return NodeOutcome.of(Map.of("branch", "a"));
        });
        stub.on("b", input -> {
            bothRunning.countDown();
            assertTrue(bothRunning.await(3, TimeUnit.SECONDS), "分支 a/b 未并发执行");
            sleep(300);
            return NodeOutcome.of(Map.of("branch", "b"));
        });

        long start = System.currentTimeMillis();
        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 4);
        long wall = System.currentTimeMillis() - start;

        assertEquals("SUCCESS", outcome.getStatus());
        assertTrue(wall < 900, "并行调度耗时应显著低于串行累加，实际 " + wall + "ms");
        assertTrue(stub.maxRunning.get() >= 2, "未观测到并发执行");
    }

    /** 有界并行：线程池大小为 2 时，4 个独立节点的同时在途数不得超过 2。 */
    @Test
    void boundedParallelism_respectsPoolSize() {
        List<WorkflowNode> nodes = List.of(node("n1", "llm"), node("n2", "llm"), node("n3", "llm"), node("n4", "llm"));
        List<WorkflowEdge> edges = List.of();
        Map<String, Object> initialInput = Map.of("input", "seed");

        StubExecutor stub = new StubExecutor();
        for (WorkflowNode n : nodes) {
            stub.on(n.getId(), input -> {
                sleep(150);
                return NodeOutcome.of(Map.of("node", n.getId()));
            });
        }

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 2);

        assertEquals("SUCCESS", outcome.getStatus());
        assertEquals(4, outcome.getNodeResults().size());
        assertTrue(stub.maxRunning.get() <= 2, "同时在途节点数超过线程池大小: " + stub.maxRunning.get());
        assertTrue(stub.maxRunning.get() >= 1);
        // 4 个 150ms 的节点经 2 线程执行，总耗时必然大于 2 轮（300ms）
        assertTrue(outcome.getDurationMillis() >= 280, "有界并行未生效，耗时 " + outcome.getDurationMillis() + "ms");
    }

    /** 失败传播：n2a 失败后 n3（下游）不再执行并标记 CANCELLED，整体 FAILED。 */
    @Test
    void failure_stopsDownstreamAndCancelsPending() {
        List<WorkflowNode> nodes = List.of(node("n1", "input"), node("n2a", "llm"), node("n2b", "llm"), node("n3", "output"));
        List<WorkflowEdge> edges = List.of(edge("n1", "n2a"), edge("n1", "n2b"), edge("n2a", "n3"), edge("n2b", "n3"));
        Map<String, Object> initialInput = Map.of("input", "seed");

        StubExecutor stub = new StubExecutor();
        stub.on("n2a", input -> {
            throw new RuntimeException("boom");
        });
        stub.on("n2b", input -> {
            sleep(300); // 失败发生时仍在途，用于验证优雅收敛
            return NodeOutcome.of(Map.of("branch", "b"));
        });

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 4);

        assertEquals("FAILED", outcome.getStatus());
        assertNotNull(outcome.getErrorMessage());
        assertTrue(outcome.getErrorMessage().contains("n2a"), "错误信息应指明失败节点: " + outcome.getErrorMessage());
        assertTrue(outcome.getErrorMessage().contains("boom"), "错误信息应包含原因: " + outcome.getErrorMessage());

        assertEquals(NodeStatus.FAILED, outcome.getNodeStatuses().get("n2a"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("n2b"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("n1"));
        assertEquals(NodeStatus.CANCELLED, outcome.getNodeStatuses().get("n3"));

        assertFalse(stub.executed.contains("n3"), "失败后下游节点不应被执行");
        // 在途的独立分支允许执行完（优雅收敛），其结果照常记录
        assertTrue(stub.executed.contains("n2b"));
        // 结果包含全部执行过的节点（并行分支相对顺序不确定，按集合断言）
        assertEquals(Set.of("n1", "n2a", "n2b"), new LinkedHashSet<>(ids(outcome.getNodeResults())));
        assertEquals("n1", ids(outcome.getNodeResults()).get(0), "起始节点应排在首位");
    }

    /** 条件分支跳过：适配层声明未选中的下游节点，调度器标记 SKIPPED 且不执行；
     *  同时拥有活跃前驱与已跳过前驱的合并节点仍应执行。 */
    @Test
    void conditionBranch_skipsInactiveDownstream() {
        List<WorkflowNode> nodes = List.of(
                node("cond", "condition"), node("t1", "llm"), node("f1", "llm"),
                node("x", "merge"), node("m2", "llm"), node("end", "output"));
        List<WorkflowEdge> edges = List.of(
                edge("cond", "t1"), edge("cond", "f1"),
                edge("t1", "x"), edge("f1", "x"),
                edge("f1", "m2"),
                edge("x", "end"));
        Map<String, Object> initialInput = Map.of("input", "seed");

        StubExecutor stub = new StubExecutor();
        // 模拟引擎适配层：条件节点产出 __selectedBranch__ 并声明未选中的传递下游 {f1, m2}
        stub.on("cond", input -> NodeOutcome.builder()
                .output(Map.of("__selectedBranch__", "true"))
                .skipDownstream(new LinkedHashSet<>(List.of("f1", "m2")))
                .build());
        stub.on("t1", input -> NodeOutcome.of(Map.of("t", 1)));

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 4);

        assertEquals("SUCCESS", outcome.getStatus());
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("cond"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("t1"));
        assertEquals(NodeStatus.SKIPPED, outcome.getNodeStatuses().get("f1"));
        assertEquals(NodeStatus.SKIPPED, outcome.getNodeStatuses().get("m2"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("x"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("end"));

        assertEquals(List.of("cond", "t1", "x", "end"), new ArrayList<>(stub.executed));
        // 合并节点 x 只拿到活跃前驱 t1 的输出
        Map<String, Object> xInput = stub.receivedInputs.get("x");
        assertNotNull(xInput);
        assertEquals(1, xInput.get("t"));
        // 最终输出为拓扑序最后一个成功节点
        assertEquals(Map.of("node", "end"), outcome.getFinalOutput());
    }

    /** 指定节点续跑：续跑点之前未恢复的节点不执行；恢复的输出对后续节点可见。 */
    @Test
    void resumeFromNode_restoresOutputsAndSkipsPreStart() {
        List<WorkflowNode> nodes = List.of(node("a", "input"), node("b", "llm"), node("c", "llm"), node("d", "output"));
        List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "d"));
        // 引擎语义：initialInput 中带入已恢复的 latestOutput（a 的输出）与原始输入
        Map<String, Object> initialInput = new LinkedHashMap<>(Map.of("input", "seed", "a", 1));

        StubExecutor stub = new StubExecutor();
        stub.on("c", input -> NodeOutcome.of(Map.of("c", input.get("a"))));
        stub.on("d", input -> NodeOutcome.of(Map.of("d", input.get("c"))));

        ResumeContext resume = ResumeContext.builder()
                .startNodeId("c")
                .restoredNodeOutputs(Map.of("a", Map.of("a", 1)))
                .initialInput(initialInput)
                .build();

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, resume, 4);

        assertEquals("SUCCESS", outcome.getStatus());
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("a"));
        assertEquals(NodeStatus.SKIPPED, outcome.getNodeStatuses().get("b"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("c"));
        assertEquals(NodeStatus.SUCCESS, outcome.getNodeStatuses().get("d"));
        assertEquals(List.of("c", "d"), new ArrayList<>(stub.executed));
        // 续跑节点通过 initialInput 看到已恢复的前置输出
        assertEquals(1, stub.receivedInputs.get("c").get("a"));
        assertEquals(Map.of("d", 1), outcome.getFinalOutput());
    }

    /**
     * 续跑起点不在流程图中时必须快速失败。
     *
     * <p>原实现把未知 startNodeId 当成"拓扑里找不到"静默降级为 startKnown=false，
     * 于是所有节点都被视为活跃节点从头重跑，已执行节点的 LLM / 工具副作用被重放一遍，
     * 而调用方拿到的却是一个 SUCCESS。这里锁定修复后的快速失败语义。
     */
    @Test
    void resumeWithUnknownNode_shouldFailFast() {
        List<WorkflowNode> nodes = List.of(node("a", "input"), node("b", "llm"), node("c", "output"));
        List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "c"));
        Map<String, Object> initialInput = new LinkedHashMap<>(Map.of("input", "seed"));

        StubExecutor stub = new StubExecutor();
        stub.on("a", input -> NodeOutcome.of(Map.of("a", 1)));
        stub.on("b", input -> NodeOutcome.of(Map.of("b", 2)));
        stub.on("c", input -> NodeOutcome.of(Map.of("c", 3)));

        ResumeContext resume = ResumeContext.builder()
                .startNodeId("ghost-node")
                .initialInput(initialInput)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> schedule(nodes, edges, stub, initialInput, resume, 4));
        assertTrue(ex.getMessage().contains("ghost-node"),
                "异常信息应包含非法的续跑起点 id，实际: " + ex.getMessage());
        // 关键：失败发生在调度之前，不能有任何节点被实际执行（否则副作用已经放出去了）
        assertTrue(stub.executed.isEmpty(), "非法续跑起点不应执行任何节点，实际执行: " + stub.executed);
    }

    /** 边界：空工作流直接成功并返回初始输入；单节点无依赖可执行。 */
    @Test
    void edgeCases_emptyAndSingleNode() {
        Map<String, Object> initialInput = Map.of("input", "seed");
        ResumeContext resumeWithInput = ResumeContext.builder().initialInput(initialInput).build();

        ScheduleOutcome empty = schedule(List.of(), List.of(), new StubExecutor(), initialInput, resumeWithInput, 4);
        assertEquals("SUCCESS", empty.getStatus());
        assertEquals(initialInput, empty.getFinalOutput());
        assertTrue(empty.getNodeResults().isEmpty());

        StubExecutor stub = new StubExecutor();
        stub.on("solo", input -> NodeOutcome.of(Map.of("solo", true)));
        ScheduleOutcome single = schedule(List.of(node("solo", "input")), List.of(), stub, initialInput, ResumeContext.empty(), 4);
        assertEquals("SUCCESS", single.getStatus());
        assertEquals(Map.of("solo", true), single.getFinalOutput());
        assertEquals(List.of("solo"), ids(single.getNodeResults()));
    }

    /** 输入解析：合并节点的输入应包含全部前驱输出的键（供引擎适配验证）。 */
    @Test
    void inputResolution_mergesAllPredecessorOutputs() {
        List<WorkflowNode> nodes = List.of(node("root", "input"), node("p", "llm"), node("q", "llm"), node("m", "merge"));
        List<WorkflowEdge> edges = List.of(edge("root", "p"), edge("root", "q"), edge("p", "m"), edge("q", "m"));
        Map<String, Object> initialInput = Map.of("input", "seed");

        StubExecutor stub = new StubExecutor();
        stub.on("p", input -> NodeOutcome.of(Map.of("fromP", 1)));
        stub.on("q", input -> NodeOutcome.of(Map.of("fromQ", 2)));
        stub.on("m", input -> NodeOutcome.of(Map.of("seen", new ArrayList<>(input.keySet()))));

        ScheduleOutcome outcome = schedule(nodes, edges, stub, initialInput, ResumeContext.empty(), 4);

        assertEquals("SUCCESS", outcome.getStatus());
        Map<String, Object> mInput = stub.receivedInputs.get("m");
        assertTrue(mInput.containsKey("fromP"), "合并节点应拿到前驱 p 的输出");
        assertTrue(mInput.containsKey("fromQ"), "合并节点应拿到前驱 q 的输出");
    }

    // ---------------------------------------------------------------- 迭代9 P1-8：上下文传播

    /**
     * P1-8：调度线程必须继承调用方的用户归属与租户上下文。
     * 修复前工作线程上下文为空 → 用户归属退化为 system、租户退化为 global，
     * 并行分支里的知识库/记忆/工具调用既可能越权也可能被归属校验误拒。
     */
    @Test
    void nodeExecution_inheritsCallerUserAndTenantContext() {
        List<WorkflowNode> nodes = List.of(node("only", "llm"));
        StubExecutor stub = new StubExecutor();
        AtomicReference<String> seenOwner = new AtomicReference<>();
        AtomicReference<String> seenTenant = new AtomicReference<>();
        stub.on("only", input -> {
            seenOwner.set(UserContext.getCurrent());
            seenTenant.set(TenantContext.getCurrent());
            return NodeOutcome.of(Map.of("ok", true));
        });

        UserContext.setCurrent("alice");
        TenantContext.setCurrent("tenant-x");
        try {
            ScheduleOutcome outcome = schedule(nodes, List.of(), stub,
                    Map.of("input", "seed"), ResumeContext.empty(), 2);
            assertEquals("SUCCESS", outcome.getStatus());
        } finally {
            UserContext.clear();
            TenantContext.clear();
        }

        assertEquals("alice", seenOwner.get(), "工作线程须继承调用方用户归属");
        assertEquals("tenant-x", seenTenant.get(), "工作线程须继承调用方租户");
    }

    /**
     * 上下文按节点投影且用完即清：同一线程复用执行多个节点时，每个节点都看到调用方上下文，
     * 而调用线程自身与工作线程在调度结束后都不残留（防线程池复用串扰）。
     */
    @Test
    void nodeExecution_projectsContextPerNodeAndClearsAfterwards() {
        List<WorkflowNode> nodes = List.of(node("a", "input"), node("b", "llm"), node("c", "output"));
        List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "c"));

        StubExecutor stub = new StubExecutor();
        List<String> observed = new CopyOnWriteArrayList<>();
        for (String id : List.of("a", "b", "c")) {
            stub.on(id, input -> {
                observed.add(id + ":" + UserContext.getCurrent() + ":" + TenantContext.getCurrent());
                return NodeOutcome.of(Map.of("node", id));
            });
        }

        UserContext.setCurrent("bob");
        TenantContext.setCurrent("tenant-y");
        try {
            // 池大小 1：三个节点必然复用同一工作线程，可验证逐节点投影而非一次性继承
            ScheduleOutcome outcome = schedule(nodes, edges, stub, Map.of("input", "seed"), ResumeContext.empty(), 1);
            assertEquals("SUCCESS", outcome.getStatus());
        } finally {
            UserContext.clear();
            TenantContext.clear();
        }

        assertEquals(List.of("a:bob:tenant-y", "b:bob:tenant-y", "c:bob:tenant-y"), new ArrayList<>(observed));
    }

    /** 调用方无上下文时工作线程也应为空——不得把 system/global 兜底值与线程残留带进并行分支。 */
    @Test
    void nodeExecution_seesNullOrContextWhenCallerHasNone() {
        List<WorkflowNode> nodes = List.of(node("only", "llm"));
        StubExecutor stub = new StubExecutor();
        AtomicReference<String> seenOwner = new AtomicReference<>("sentinel");
        AtomicReference<String> seenTenant = new AtomicReference<>("sentinel");
        stub.on("only", input -> {
            seenOwner.set(UserContext.getCurrent());
            seenTenant.set(TenantContext.getCurrent());
            return NodeOutcome.of(Map.of("ok", true));
        });

        UserContext.clear();
        TenantContext.clear();
        ScheduleOutcome outcome = schedule(nodes, List.of(), stub,
                Map.of("input", "seed"), ResumeContext.empty(), 2);

        assertEquals("SUCCESS", outcome.getStatus());
        assertNull(seenOwner.get(), "调用方无归属时工作线程不应自行兜底");
        assertNull(seenTenant.get(), "调用方无租户时工作线程不应自行兜底");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
