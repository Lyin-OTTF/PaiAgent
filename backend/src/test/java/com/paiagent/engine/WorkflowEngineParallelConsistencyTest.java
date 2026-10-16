package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 迭代4 · 串行/并行双跑一致性（引擎级集成测试）。
 *
 * <p>同一条菱形工作流分别以串行（默认）与有界并行（paiagent.engine.parallel-enabled=true）
 * 两种模式执行，验证：最终输出、节点结果、事件集合完全一致；并行模式真实发生并发；
 * 并行模式下节点快照正确落库。
 */
@SpringBootTest(properties = {
        "paiagent.auth.jwt-secret=test-jwt-secret-key-for-spring-context-123456",
        "minio.endpoint=http://localhost:9000",
        "minio.accessKey=minioadmin",
        "minio.secretKey=minioadmin",
        "minio.bucketName=paiagent",
        "minio.publicUrl=http://localhost:9000",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.datasource.password=${MYSQL_PASSWORD:1234}"
})
class WorkflowEngineParallelConsistencyTest {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NodeExecutorFactory nodeExecutorFactory;

    private final AtomicReference<StubNodeExecutor> currentExecutor = new AtomicReference<>();
    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(nodeExecutorFactory.getExecutor(anyString())).thenAnswer(invocation -> currentExecutor.get());
    }

    @AfterEach
    void tearDown() {
        for (Long id : executionIds) {
            jdbcTemplate.update("DELETE FROM execution_snapshot WHERE execution_id = ?", id);
            jdbcTemplate.update("DELETE FROM execution_record WHERE id = ?", id);
        }
        executionIds.clear();
    }

    @Test
    void serialAndParallel_produceIdenticalResults() {
        Workflow workflow = buildDiamondWorkflow();

        // ---------- 串行执行（开关默认关闭） ----------
        ReflectionTestUtils.setField(workflowEngine, "parallelEnabled", false);
        StubNodeExecutor serialStub = new StubNodeExecutor(false, null);
        currentExecutor.set(serialStub);
        List<String> serialEvents = new CopyOnWriteArrayList<>();
        ExecutionResponse serial = workflowEngine.executeWithCallback(workflow, "seed",
                event -> serialEvents.add(event.getEventType()));
        executionIds.add(serial.getExecutionId());

        // ---------- 有界并行执行 ----------
        ReflectionTestUtils.setField(workflowEngine, "parallelEnabled", true);
        StubNodeExecutor parallelStub = new StubNodeExecutor(true, null);
        currentExecutor.set(parallelStub);
        List<String> parallelEvents = new CopyOnWriteArrayList<>();
        ExecutionResponse parallel = workflowEngine.executeWithCallback(workflow, "seed",
                event -> parallelEvents.add(event.getEventType()));
        executionIds.add(parallel.getExecutionId());

        // 1) 整体状态与最终输出一致
        assertEquals("SUCCESS", serial.getStatus());
        assertEquals("SUCCESS", parallel.getStatus());
        assertEquals(serial.getOutputData(), parallel.getOutputData(), "串行与并行的最终输出应一致");
        assertEquals("{\"merged\":\"a+b\"}", parallel.getOutputData());

        // 2) 节点结果一致（忽略耗时）
        assertEquals(nodeSummaries(serial.getNodeResults()), nodeSummaries(parallel.getNodeResults()),
                "串行与并行的节点结果应一致");
        assertEquals(4, parallel.getNodeResults().size());

        // 3) 并行确实发生：两条独立分支同时运行过，且四个节点都执行过
        assertTrue(parallelStub.maxRunning.get() >= 2, "并行模式未观测到并发执行");
        assertEquals(Set.of("n1", "n2a", "n2b", "n3"), new HashSet<>(parallelStub.executed));

        // 4) 事件完备且两种模式一致
        Set<String> serialEventTypes = new HashSet<>(serialEvents);
        Set<String> parallelEventTypes = new HashSet<>(parallelEvents);
        assertEquals(Set.of("WORKFLOW_START", "NODE_START", "NODE_SUCCESS", "WORKFLOW_COMPLETE"), serialEventTypes);
        assertEquals(serialEventTypes, parallelEventTypes);
        assertEquals(4, parallelEvents.stream().filter("NODE_SUCCESS"::equals).count());
        assertEquals("WORKFLOW_COMPLETE", parallelEvents.get(parallelEvents.size() - 1));

        // 5) 并行模式快照落库：4 个节点全部成功
        List<ExecutionSnapshot> snapshots = executionSnapshotMapper.selectByExecutionId(parallel.getExecutionId());
        assertEquals(4, snapshots.size());
        assertTrue(snapshots.stream().allMatch(s -> "SUCCESS".equals(s.getStatus())),
                "并行模式所有节点快照应为 SUCCESS");
        Map<String, Integer> orderByNode = new HashMap<>();
        for (ExecutionSnapshot snapshot : snapshots) {
            orderByNode.put(snapshot.getNodeId(), snapshot.getExecutionOrder());
        }
        assertTrue(orderByNode.get("n1") < orderByNode.get("n3"), "起始节点执行序应早于汇聚节点");
        assertTrue(orderByNode.get("n2a") < orderByNode.get("n3"));
        assertTrue(orderByNode.get("n2b") < orderByNode.get("n3"));
    }

    /**
     * 迭代4 遗留补齐 · 失败场景双跑等价性。
     *
     * <p>同一菱形工作流（n2a 抛异常）分别以两种模式执行，验证：
     * 整体状态、错误信息（格式与内容）、共同执行前缀（n1 SUCCESS / n2a FAILED）完全一致；
     * 模式差异是设计使然且各自语义正确——串行 fail-stop（失败后不再执行任何节点），
     * 并行 fail-fast + 优雅收敛（已启动的独立分支跑完，未启动的下游节点不执行：
     * 调度器内部标记 CANCELLED，但不产生节点结果与快照，与串行对 n3 的缺席一致）。
     */
    @Test
    void serialAndParallel_failFastEquivalence() {
        Workflow workflow = buildDiamondWorkflow();

        // ---------- 串行 fail-stop ----------
        ReflectionTestUtils.setField(workflowEngine, "parallelEnabled", false);
        // 失败场景不开闭锁：n2a 抛异常时尚未 countDown，若开闭锁 n2b 会空等 3s 反被拖失败
        StubNodeExecutor serialStub = new StubNodeExecutor(false, "n2a");
        currentExecutor.set(serialStub);
        List<String> serialEvents = new CopyOnWriteArrayList<>();
        ExecutionResponse serial = workflowEngine.executeWithCallback(workflow, "seed",
                event -> serialEvents.add(event.getEventType()));
        executionIds.add(serial.getExecutionId());

        // ---------- 并行 fail-fast + 优雅收敛 ----------
        ReflectionTestUtils.setField(workflowEngine, "parallelEnabled", true);
        StubNodeExecutor parallelStub = new StubNodeExecutor(false, "n2a");
        currentExecutor.set(parallelStub);
        List<String> parallelEvents = new CopyOnWriteArrayList<>();
        ExecutionResponse parallel = workflowEngine.executeWithCallback(workflow, "seed",
                event -> parallelEvents.add(event.getEventType()));
        executionIds.add(parallel.getExecutionId());

        // 1) 整体状态、错误信息（串行格式的复刻）、最终输出完全一致
        assertEquals("FAILED", serial.getStatus());
        assertEquals("FAILED", parallel.getStatus());
        assertEquals("节点 n2a 执行失败: boom", serial.getErrorMessage());
        assertEquals(serial.getErrorMessage(), parallel.getErrorMessage(),
                "并行路径的错误信息格式应与串行一致");
        assertNull(serial.getOutputData());
        assertNull(parallel.getOutputData());

        // 2) 共同执行前缀一致：n1 成功且输出一致，n2a 失败
        Map<String, ExecutionResponse.NodeResult> serialByNode = byNode(serial.getNodeResults());
        Map<String, ExecutionResponse.NodeResult> parallelByNode = byNode(parallel.getNodeResults());
        assertEquals("SUCCESS", serialByNode.get("n1").getStatus());
        assertEquals("SUCCESS", parallelByNode.get("n1").getStatus());
        assertEquals(serialByNode.get("n1").getOutput(), parallelByNode.get("n1").getOutput());
        assertEquals("FAILED", serialByNode.get("n2a").getStatus());
        assertEquals("FAILED", parallelByNode.get("n2a").getStatus());

        // 3) 模式差异（设计使然，各自语义正确）
        assertFalse(serialByNode.containsKey("n2b"), "串行 fail-stop：失败后不应再执行后续节点");
        assertFalse(serialByNode.containsKey("n3"), "串行 fail-stop：汇聚节点不应执行");
        assertEquals("SUCCESS", parallelByNode.get("n2b").getStatus(),
                "并行优雅收敛：已启动的独立分支应跑完");
        assertFalse(parallelByNode.containsKey("n3"),
                "并行 fail-fast：未启动的下游节点不产生节点结果（调度器内部标记 CANCELLED，与串行对 n3 的缺席一致）");

        // 4) 事件语义一致：NODE_ERROR 各 1 次且收尾于 WORKFLOW_COMPLETE；
        //    成功节点数差异来自优雅收敛（串行 1 个 = n1；并行 2 个 = n1 + n2b）
        assertEquals(1, serialEvents.stream().filter("NODE_ERROR"::equals).count());
        assertEquals(1, parallelEvents.stream().filter("NODE_ERROR"::equals).count());
        assertEquals("WORKFLOW_COMPLETE", serialEvents.get(serialEvents.size() - 1));
        assertEquals("WORKFLOW_COMPLETE", parallelEvents.get(parallelEvents.size() - 1));
        assertEquals(1, serialEvents.stream().filter("NODE_SUCCESS"::equals).count());
        assertEquals(2, parallelEvents.stream().filter("NODE_SUCCESS"::equals).count());

        // 5) 并行快照终态：失败节点 FAILED、收敛分支 SUCCESS；
        //    n3 未启动即取消，与串行模式一致地不留快照
        List<ExecutionSnapshot> snapshots = executionSnapshotMapper.selectByExecutionId(parallel.getExecutionId());
        Map<String, String> snapshotStatus = new HashMap<>();
        for (ExecutionSnapshot snapshot : snapshots) {
            snapshotStatus.put(snapshot.getNodeId(), snapshot.getStatus());
        }
        assertEquals("FAILED", snapshotStatus.get("n2a"));
        assertEquals("SUCCESS", snapshotStatus.get("n2b"));
        assertFalse(snapshotStatus.containsKey("n3"), "取消节点未启动，不应有快照");
    }

    private Map<String, ExecutionResponse.NodeResult> byNode(List<ExecutionResponse.NodeResult> nodeResults) {
        Map<String, ExecutionResponse.NodeResult> map = new HashMap<>();
        for (ExecutionResponse.NodeResult r : nodeResults) {
            map.put(r.getNodeId(), r);
        }
        return map;
    }

    /** 提取节点关键信息用于跨模式对比（剔除耗时与每次执行特有的运行时身份）。 */
    private List<Map<String, String>> nodeSummaries(List<ExecutionResponse.NodeResult> nodeResults) {
        return nodeResults.stream()
                .map(r -> {
                    Map<String, String> summary = new LinkedHashMap<>();
                    summary.put("nodeId", r.getNodeId());
                    summary.put("status", r.getStatus());
                    summary.put("input", normalizeInput(r.getInput()));
                    summary.put("output", r.getOutput());
                    return summary;
                })
                .collect(Collectors.toList());
    }

    /**
     * 归一化节点输入快照：剔除执行身份（executionId/workflowId）。
     *
     * <p>迭代6 起引擎会把「本次执行特有」的身份注入节点输入，两次独立执行（串行/并行各一次）
     * 的 executionId 必然不同，这属于运行环境差异而非串行 vs 并行的语义差异，比较时应忽略。
     */
    private String normalizeInput(String inputJson) {
        if (inputJson == null) {
            return null;
        }
        Map<String, Object> parsed = JSON.parseObject(inputJson, Map.class);
        parsed.remove("executionId");
        parsed.remove("workflowId");
        return JSON.toJSONString(parsed);
    }

    private Workflow buildDiamondWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("双跑一致性测试工作流");

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(node("n1", "input"));
        nodes.add(node("n2a", "llm"));
        nodes.add(node("n2b", "llm"));
        nodes.add(node("n3", "output"));

        List<WorkflowEdge> edges = new ArrayList<>();
        edges.add(edge("n1", "n2a"));
        edges.add(edge("n1", "n2b"));
        edges.add(edge("n2a", "n3"));
        edges.add(edge("n2b", "n3"));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
    }

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

    /**
     * 桩节点执行器：按节点 ID 返回确定性输出；
     * barrierEnabled 时两条分支以闭锁相互等待，用于证明并行模式真实并发（仅 happy path）；
     * failNodeId 指定的节点抛异常，用于失败场景双跑等价性验证（此时不应开闭锁，
     * 否则未失败分支会空等超时反而被拖失败）。
     */
    private static final class StubNodeExecutor implements NodeExecutor {

        private final boolean barrierEnabled;
        private final String failNodeId;
        private final CountDownLatch bothBranches = new CountDownLatch(2);
        private final List<String> executed = new CopyOnWriteArrayList<>();
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();

        private StubNodeExecutor(boolean barrierEnabled, String failNodeId) {
            this.barrierEnabled = barrierEnabled;
            this.failNodeId = failNodeId;
        }

        @Override
        public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
            executed.add(node.getId());
            if (node.getId().equals(failNodeId)) {
                throw new RuntimeException("boom");
            }
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                switch (node.getId()) {
                    case "n1":
                        return Map.of("start", String.valueOf(input.get("input")));
                    case "n2a":
                    case "n2b":
                        awaitPeer();
                        return node.getId().equals("n2a") ? Map.of("fromA", "a") : Map.of("fromB", "b");
                    case "n3":
                        return Map.of("merged", input.get("fromA") + "+" + input.get("fromB"));
                    default:
                        return Map.of("node", node.getId());
                }
            } finally {
                running.decrementAndGet();
            }
        }

        /** 仅 happy path 并行模式开启：等待另一分支就绪，否则无法证明并发。 */
        private void awaitPeer() {
            if (!barrierEnabled) {
                return;
            }
            bothBranches.countDown();
            try {
                assertTrue(bothBranches.await(3, TimeUnit.SECONDS), "两条独立分支未并发执行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String getSupportedNodeType() {
            return "stub";
        }
    }
}
