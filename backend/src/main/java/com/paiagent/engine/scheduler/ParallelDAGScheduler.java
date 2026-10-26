package com.paiagent.engine.scheduler;

import com.paiagent.common.UserContext;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界并行 DAG 调度器（迭代4 / P1-1）
 *
 * <p>核心能力：
 * <ul>
 *   <li>就绪队列调度：仅当前驱全部终结后才调度节点，天然满足依赖顺序；</li>
 *   <li>有界并行：固定大小线程池限制同时在途节点数，超出部分进入有界队列等待；</li>
 *   <li>失败传播（fail-fast）：任一节点失败即停止调度新节点，未开始节点标记 CANCELLED；</li>
 *   <li>优雅收敛：失败时已在途的节点允许执行完（结果照常记录），不会中断线程；</li>
 * <li>条件分支跳过：执行适配层声明未选中的下游节点，调度器标记 SKIPPED 并正确释放依赖计数；</li>
 *   <li>指定节点续跑：通过 {@link ResumeContext} 恢复历史输出，续跑点之前的未恢复节点不执行；</li>
 *   <li>最终输出语义与串行引擎一致：拓扑序中最后一个成功节点的输出。</li>
 * </ul>
 *
 * <p>调度器本身无状态（全部运行期状态封装在方法内部），可安全作为单例复用。
 */
@Slf4j
@Component
public class ParallelDAGScheduler {

    /**
     * 执行一次 DAG 调度
     *
     * @param topoNodes      拓扑排序后的节点列表（由 DAGParser 产出）
     * @param edges          工作流边列表
     * @param taskExecutor   节点任务执行器
     * @param inputResolver  节点输入解析器
     * @param resumeContext  续跑上下文（全新执行传 {@link ResumeContext#empty()}）
     * @param schedulerConfig 调度配置（传 null 使用默认值）
     */
    public ScheduleOutcome schedule(List<WorkflowNode> topoNodes,
                                    List<WorkflowEdge> edges,
                                    NodeTaskExecutor taskExecutor,
                                    InputResolver inputResolver,
                                    ResumeContext resumeContext,
                                    SchedulerConfig schedulerConfig) {
        Objects.requireNonNull(topoNodes, "topoNodes 不能为空");
        Objects.requireNonNull(taskExecutor, "taskExecutor 不能为空");
        Objects.requireNonNull(inputResolver, "inputResolver 不能为空");

        SchedulerConfig config = schedulerConfig != null ? schedulerConfig : SchedulerConfig.defaults();
        ResumeContext resume = resumeContext != null ? resumeContext : ResumeContext.empty();
        int poolSize = Math.max(1, config.getPoolSize());

        RunState state = initState(topoNodes, edges, resume);
        long deadlineMillis = System.currentTimeMillis() + config.getShutdownTimeoutSeconds() * 1000L;

        // 迭代9 P1-8：调用方（HTTP 请求线程 / HITL 补执行线程）的归属与租户上下文
        // 不会自动继承到工作线程。若不显式传递，并行分支里的知识库/记忆/工具调用
        // 会以「无上下文」执行——用户归属退化为 system、租户退化为 global，
        // 既可能越权也可能被后续的归属校验误拒。
        String ownerContext = UserContext.getCurrent();
        String tenantContext = TenantContext.getCurrent();

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new SchedulerThreadFactory(config.getThreadNamePrefix()),
                new ThreadPoolExecutor.CallerRunsPolicy());

        long startTime = System.currentTimeMillis();
        try {
            dispatchLoop(state, pool, poolSize, taskExecutor, inputResolver, ownerContext, tenantContext);
            drain(state, deadlineMillis);
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(config.getShutdownTimeoutSeconds(), TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }

        return assembleOutcome(state, topoNodes, resume, System.currentTimeMillis() - startTime);
    }

    // ---------------------------------------------------------------- 调度主循环

    /**
     * 派发循环：把就绪节点提交到线程池（受并行度约束），失败后立即停止派发。
     */
    private void dispatchLoop(RunState state,
                              ThreadPoolExecutor pool,
                              int poolSize,
                              NodeTaskExecutor taskExecutor,
                              InputResolver inputResolver,
                              String ownerContext,
                              String tenantContext) {
        dispatch:
        while (true) {
            List<String> batch = new ArrayList<>();
            synchronized (state.lock) {
                // 等待条件：无就绪节点、或线程池已满（在途达到并行度上限）
                while ((state.ready.isEmpty() || state.inFlight >= poolSize)
                        && state.inFlight > 0 && !state.failed) {
                    try {
                        state.lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        markInterrupted(state, e);
                        break dispatch;
                    }
                }
                // 失败快速止损：不再调度任何新节点
                if (state.failed) {
                    break;
                }
                // 就绪队列与在途任务都为空：全部完成（或遇到无法调度的节点）
                if (state.ready.isEmpty() && state.inFlight == 0) {
                    break;
                }
                int capacity = poolSize - state.inFlight;
                while (capacity > 0 && !state.ready.isEmpty()) {
                    String nodeId = state.ready.poll();
                    // 防御：出队后可能已被跳过声明标记为 SKIPPED，不再调度
                    if (state.status.get(nodeId) != NodeStatus.PENDING) {
                        continue;
                    }
                    state.status.put(nodeId, NodeStatus.RUNNING);
                    batch.add(nodeId);
                    capacity--;
                }
                state.inFlight += batch.size();
            }
            for (String nodeId : batch) {
                pool.execute(() -> runNode(state, nodeId, taskExecutor, inputResolver, ownerContext, tenantContext));
            }
        }
    }

    /**
     * 优雅收敛：失败后等待在途节点执行完毕，保证结果完整且线程池可安静退出。
     */
    private void drain(RunState state, long deadlineMillis) {
        synchronized (state.lock) {
            while (state.inFlight > 0 && System.currentTimeMillis() < deadlineMillis) {
                try {
                    state.lock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ---------------------------------------------------------------- 节点执行

    private void runNode(RunState state,
                         String nodeId,
                         NodeTaskExecutor taskExecutor,
                         InputResolver inputResolver,
                         String ownerContext,
                         String tenantContext) {
        // 迭代9 P1-8：显式投影调用方上下文；finally 清理，防线程池复用串扰
        UserContext.setCurrent(ownerContext);
        TenantContext.setCurrent(tenantContext);
        try {
            runNodeInternal(state, nodeId, taskExecutor, inputResolver);
        } finally {
            UserContext.clear();
            TenantContext.clear();
        }
    }

    private void runNodeInternal(RunState state,
                                 String nodeId,
                                 NodeTaskExecutor taskExecutor,
                                 InputResolver inputResolver) {
        WorkflowNode node = state.nodeMap.get(nodeId);
        Map<String, Object> input = null;
        long nodeStart = System.currentTimeMillis();
        try {
            synchronized (state.lock) {
                // 传入输出快照，避免执行器看到并发写入的 Map
                input = inputResolver.resolve(node, new LinkedHashMap<>(state.nodeOutputs));
            }
            NodeOutcome outcome = taskExecutor.execute(node, input);
            Map<String, Object> output = outcome != null && outcome.getOutput() != null
                    ? outcome.getOutput()
                    : new LinkedHashMap<>();
            int duration = (int) (System.currentTimeMillis() - nodeStart);
            synchronized (state.lock) {
                state.nodeOutputs.put(nodeId, output);
                state.results.put(nodeId, NodeResult.builder()
                        .nodeId(nodeId)
                        .nodeName(node.getType())
                        .status(NodeStatus.SUCCESS)
                        .input(input)
                        .output(output)
                        .duration(duration)
                        .build());
                state.status.put(nodeId, NodeStatus.SUCCESS);
                if (outcome != null && outcome.getSkipDownstream() != null) {
                    for (String skipId : outcome.getSkipDownstream()) {
                        applySkip(state, skipId);
                    }
                }
                onNodeFinished(state, nodeId, true);
            }
        } catch (Throwable t) {
            int duration = (int) (System.currentTimeMillis() - nodeStart);
            synchronized (state.lock) {
                state.results.put(nodeId, NodeResult.builder()
                        .nodeId(nodeId)
                        .nodeName(node.getType())
                        .status(NodeStatus.FAILED)
                        .input(input)
                        .duration(duration)
                        .error(errorMessageOf(t))
                        .build());
                state.status.put(nodeId, NodeStatus.FAILED);
                if (!state.failed) {
                    state.failed = true;
                    state.failure = t;
                    state.failedNodeId = nodeId;
                }
                onNodeFinished(state, nodeId, false);
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("并行调度节点执行失败: {} - {}", nodeId, errorMessageOf(t));
        }
    }

    // ---------------------------------------------------------------- 状态迁移（调用方需持有锁）

    /**
     * 节点终结：递减在途与剩余活跃计数；成功且整体未失败时释放后继的依赖计数。
     */
    private void onNodeFinished(RunState state, String nodeId, boolean success) {
        state.inFlight--;
        state.remainingActive--;
        if (success && !state.failed) {
            for (String successor : state.succs.getOrDefault(nodeId, List.of())) {
                onPredFinished(state, successor);
            }
        }
        state.lock.notifyAll();
    }

    /**
     * 前驱终结：待调度节点的未决前驱数减一，归零则进入就绪队列。
     */
    private void onPredFinished(RunState state, String nodeId) {
        if (state.status.get(nodeId) != NodeStatus.PENDING) {
            return;
        }
        int remaining = state.pendingDeps.merge(nodeId, -1, Integer::sum);
        if (remaining <= 0) {
            state.ready.offer(nodeId);
        }
    }

    /**
     * 条件分支跳过：标记为 SKIPPED（无输出），并按“已终结前驱”释放后继依赖计数。
     * 同时从就绪队列摘除——同批跳过声明中，先处理的跳过可能已把后处理的节点释放进队列。
     */
    private void applySkip(RunState state, String nodeId) {
        if (state.status.get(nodeId) != NodeStatus.PENDING) {
            return;
        }
        state.status.put(nodeId, NodeStatus.SKIPPED);
        state.ready.remove(nodeId);
        state.remainingActive--;
        for (String successor : state.succs.getOrDefault(nodeId, List.of())) {
            onPredFinished(state, successor);
        }
    }

    private void markInterrupted(RunState state, Throwable cause) {
        state.failed = true;
        state.failure = cause;
        state.failedNodeId = null;
    }

    // ---------------------------------------------------------------- 初始化与汇总

    private RunState initState(List<WorkflowNode> topoNodes, List<WorkflowEdge> edges, ResumeContext resume) {
        RunState state = new RunState();
        Map<String, Integer> topoIndex = new HashMap<>();
        for (int i = 0; i < topoNodes.size(); i++) {
            WorkflowNode node = topoNodes.get(i);
            state.nodeMap.put(node.getId(), node);
            topoIndex.put(node.getId(), i);
            state.preds.put(node.getId(), new ArrayList<>());
            state.succs.put(node.getId(), new ArrayList<>());
            state.status.put(node.getId(), NodeStatus.PENDING);
            state.pendingDeps.put(node.getId(), 0);
        }

        if (edges != null) {
            for (WorkflowEdge edge : edges) {
                String source = edge.getSource();
                String target = edge.getTarget();
                if (source == null || target == null
                        || !state.nodeMap.containsKey(source) || !state.nodeMap.containsKey(target)) {
                    continue;
                }
                state.preds.get(target).add(source);
                state.succs.get(source).add(target);
            }
        }

        Map<String, Map<String, Object>> restored = resume.getRestoredNodeOutputs() != null
                ? resume.getRestoredNodeOutputs()
                : Map.of();
        Set<String> skippedSeed = resume.getSkippedNodes() != null ? resume.getSkippedNodes() : Set.of();
        String startNodeId = resume.getStartNodeId();
        boolean startKnown = startNodeId != null && topoIndex.containsKey(startNodeId);
        // 起点不在拓扑里时不能静默按「全跑」处理：那样会把已执行节点的副作用再放一遍。
        // 引擎侧 resolveResumeStartNodeId 已前置校验，这里是调度器独立使用时的兜底。
        if (startNodeId != null && !startKnown) {
            throw new IllegalArgumentException("续跑起点不存在于流程图拓扑中: " + startNodeId);
        }

        for (WorkflowNode node : topoNodes) {
            String id = node.getId();
            // 1. 已恢复输出的节点：视为成功，不重新执行
            if (restored.containsKey(id)) {
                state.status.put(id, NodeStatus.SUCCESS);
                state.nodeOutputs.put(id, restored.get(id));
                continue;
            }
            // 2. 续跑点之前且未恢复的节点：不执行（与串行引擎的 continue 语义一致）
            if (startKnown && topoIndex.get(id) < topoIndex.get(startNodeId)) {
                state.status.put(id, NodeStatus.SKIPPED);
                continue;
            }
            // 3. 条件分支未选中的节点：跳过
            if (skippedSeed.contains(id)) {
                state.status.put(id, NodeStatus.SKIPPED);
                continue;
            }
            // 4. 活跃节点：统计未决前驱数（前驱在拓扑序中更早，状态已定型）
            state.remainingActive++;
            int pending = 0;
            for (String pred : state.preds.get(id)) {
                if (state.status.get(pred) == NodeStatus.PENDING) {
                    pending++;
                }
            }
            state.pendingDeps.put(id, pending);
            if (pending == 0) {
                state.ready.offer(id);
            }
        }
        return state;
    }

    private ScheduleOutcome assembleOutcome(RunState state,
                                            List<WorkflowNode> topoNodes,
                                            ResumeContext resume,
                                            long durationMillis) {
        synchronized (state.lock) {
            boolean stuck = false;
            for (WorkflowNode node : topoNodes) {
                NodeStatus current = state.status.get(node.getId());
                if (current == NodeStatus.PENDING || current == NodeStatus.RUNNING) {
                    state.status.put(node.getId(), NodeStatus.CANCELLED);
                    stuck = true;
                }
            }
            if (stuck && !state.failed) {
                state.failed = true;
                state.failure = new IllegalStateException("DAG 调度异常: 存在无法被调度的节点");
                state.failedNodeId = null;
            }

            List<NodeResult> nodeResults = new ArrayList<>();
            Map<String, Object> finalOutput = resume.getInitialInput() != null
                    ? resume.getInitialInput()
                    : new LinkedHashMap<>();
            for (WorkflowNode node : topoNodes) {
                NodeResult result = state.results.get(node.getId());
                if (result == null) {
                    continue;
                }
                nodeResults.add(result);
                if (result.getStatus() == NodeStatus.SUCCESS && result.getOutput() != null) {
                    finalOutput = result.getOutput();
                }
            }

            String errorMessage = null;
            if (state.failed) {
                errorMessage = state.failedNodeId != null
                        ? "节点 " + state.failedNodeId + " 执行失败: " + errorMessageOf(state.failure)
                        : errorMessageOf(state.failure);
            }

            return ScheduleOutcome.builder()
                    .status(state.failed ? "FAILED" : "SUCCESS")
                    .errorMessage(errorMessage)
                    .finalOutput(finalOutput)
                    .nodeResults(nodeResults)
                    .nodeStatuses(new LinkedHashMap<>(state.status))
                    .durationMillis(durationMillis)
                    .build();
        }
    }

    private String errorMessageOf(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.toString();
    }

    // ---------------------------------------------------------------- 内部结构

    /**
     * 一次调度的全部运行期状态（所有访问均持有 lock）
     */
    private static final class RunState {
        private final Object lock = new Object();
        private final Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        private final Map<String, List<String>> preds = new LinkedHashMap<>();
        private final Map<String, List<String>> succs = new LinkedHashMap<>();
        private final Map<String, NodeStatus> status = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
        private final Map<String, NodeResult> results = new LinkedHashMap<>();
        private final Map<String, Integer> pendingDeps = new HashMap<>();
        private final Deque<String> ready = new ArrayDeque<>();
        private int inFlight;
        private int remainingActive;
        private boolean failed;
        private Throwable failure;
        private String failedNodeId;
    }

    /**
     * 守护线程工厂：避免线程池线程阻止 JVM 退出
     */
    private static final class SchedulerThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        private SchedulerThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
