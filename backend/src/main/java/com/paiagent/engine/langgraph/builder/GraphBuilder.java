package com.paiagent.engine.langgraph.builder;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.langgraph.adapter.NodeAdapter;
import com.paiagent.engine.langgraph.loop.LoopNode;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 图构建器（迭代5：条件边 + 循环守卫 + Checkpoint 装配）。
 *
 * <p>将工作流配置转换为 LangGraph StateGraph。相对初版的改进：
 * <ul>
 *   <li><b>条件边</b>：条件节点（type=condition 且出边带 sourceHandle）改用
 *       {@code addConditionalEdges}，由 {@link ConditionalRouter} 决定分支目标，
 *       未选中的分支不再被无条件执行；</li>
 *   <li><b>循环守卫</b>：识别成环的回边，把环入口节点用 {@link LoopNode} 包装，
 *       按 {@code data.maxIterations} 限制迭代次数，超限抛 {@code LoopLimitExceededException}；</li>
 *   <li><b>多出口</b>：所有没有出边的节点都接到 END，修复原来只接一个出口、
 *       其余终端节点运行期报 missingEdge 的问题；</li>
 *   <li><b>Checkpoint</b>：存在 {@link BaseCheckpointSaver} 时以 CompileConfig 装配，
 *       支撑 LangGraph 断点恢复；</li>
 *   <li><b>全局迭代上限修正</b>：langgraph4j 默认 25 次上限按「节点数」计量，
 *       超过 24 个节点的线性工作流会被静默截断，这里按图规模放大。</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphBuilder {

    /** 全局迭代上限兜底值：保证较长线性工作流不会被默认 25 的上限截断。 */
    private static final int GLOBAL_ITERATION_FLOOR = 200;

    @Autowired
    private NodeAdapter nodeAdapter;

    /** 检查点持久化；不存在时图不带 checkpointSaver（断点恢复能力关闭）。 */
    @Autowired(required = false)
    private BaseCheckpointSaver checkpointSaver;

    public void setCheckpointSaver(BaseCheckpointSaver checkpointSaver) {
        this.checkpointSaver = checkpointSaver;
    }

    /**
     * 构建 LangGraph StateGraph
     *
     * @param config 工作流配置
     * @param eventCallback 事件回调（可选）
     * @return 编译后的 StateGraph
     * @throws Exception 图构建异常
     */
    public CompiledGraph<AgentState> buildGraph(
            WorkflowConfig config,
            Consumer<ExecutionEvent> eventCallback) throws Exception {

        List<WorkflowNode> nodes = config.getNodes() != null ? config.getNodes() : List.of();
        List<WorkflowEdge> edges = config.getEdges() != null ? config.getEdges() : List.of();

        log.info("开始构建 LangGraph: 节点数={}, 边数={}", nodes.size(), edges.size());

        Map<String, List<WorkflowEdge>> edgesBySource = indexBySource(nodes, edges);
        Set<String> loopEntryNodes = detectLoopEntries(nodes, edges);

        // 创建 StateGraph
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        // 添加所有节点（环入口节点套循环守卫）
        addNodes(graph, nodes, loopEntryNodes, eventCallback);

        // 添加所有边（条件节点走条件边）
        addEdges(graph, nodes, edgesBySource);

        // 设置入口和出口
        setEntryAndExit(graph, nodes, edgesBySource);

        // 编译图
        CompiledGraph<AgentState> compiled = compile(graph);

        // 全局迭代上限兜底（节点级 LoopNode 才是精确控制）
        int maxLoopIterations = maxLoopIterations(nodes, loopEntryNodes);
        compiled.setMaxIterations(globalIterationLimit(nodes.size(), maxLoopIterations));

        log.info("LangGraph 构建完成: 条件节点={}, 循环入口={}, 全局迭代上限={}",
                countConditionalNodes(nodes, edgesBySource), loopEntryNodes, maxLoopIterations);
        return compiled;
    }

    /**
     * 添加节点到图中
     */
    private void addNodes(
            StateGraph<AgentState> graph,
            List<WorkflowNode> nodes,
            Set<String> loopEntryNodes,
            Consumer<ExecutionEvent> eventCallback) throws Exception {

        for (WorkflowNode node : nodes) {
            log.debug("添加节点: id={}, type={}", node.getId(), node.getType());

            // 使用 NodeAdapter 将节点适配为 LangGraph NodeAction
            var nodeAction = nodeAdapter.adaptNode(node, eventCallback);

            if (loopEntryNodes.contains(node.getId())) {
                int maxIterations = maxIterationsOf(node);
                nodeAction = LoopNode.wrap(node.getId(), maxIterations, nodeAction);
                log.info("节点 [{}] 为循环入口，装配迭代守卫 maxIterations={}", node.getId(), maxIterations);
            }

            graph.addNode(node.getId(), nodeAction);
        }
    }

    /**
     * 添加边到图中。
     *
     * <p>条件节点走 {@code addConditionalEdges}（单一条件边即可表达全部分支）；
     * 普通节点只支持单出边——LangGraph4j 的 Edge 以 sourceId 为唯一键，
     * 同一源节点加两条普通边会抛 duplicateEdgeError。
     */
    private void addEdges(
            StateGraph<AgentState> graph,
            List<WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> edgesBySource) throws Exception {

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNode node : nodes) {
            nodeIds.add(node.getId());
        }

        for (WorkflowNode node : nodes) {
            List<WorkflowEdge> outEdges = edgesBySource.getOrDefault(node.getId(), List.of());
            if (outEdges.isEmpty()) {
                continue;
            }

            if (ConditionalRouter.isConditionalNode(node, outEdges)) {
                Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, outEdges);
                // 悬空目标收敛到 END，避免 compile 阶段抛 missingNodeInEdgeMapping
                mappings.replaceAll((branch, target) ->
                        (StateGraph.END.equals(target) || nodeIds.contains(target)) ? target : StateGraph.END);
                AsyncEdgeAction<AgentState> route = state ->
                        CompletableFuture.completedFuture(ConditionalRouter.resolveBranch(state.data()));
                graph.addConditionalEdges(node.getId(), route, mappings);
                log.info("节点 [{}] 使用条件边，分支映射={}", node.getId(), mappings);
                continue;
            }

            if (outEdges.size() > 1) {
                throw new IllegalArgumentException(String.format(
                        "LangGraph 引擎暂不支持节点 [%s] 的多个普通出边（共 %d 条）："
                                + "请改用条件节点（condition）表达分支，或为该节点只保留一条出边",
                        node.getId(), outEdges.size()));
            }

            WorkflowEdge edge = outEdges.get(0);
            if (!nodeIds.contains(edge.getTarget())) {
                log.warn("忽略悬空边: {} -> {}（目标节点不存在）", edge.getSource(), edge.getTarget());
                continue;
            }
            log.debug("添加边: {} -> {}", edge.getSource(), edge.getTarget());
            graph.addEdge(edge.getSource(), edge.getTarget());
        }
    }

    /**
     * 设置图的入口和出口节点。
     *
     * <p>入口：没有入边的第一个节点；出口：<b>所有</b>没有出边的节点都接到 END
     * （原来只接一个，多出口工作流会在运行期报 missingEdge）。
     */
    private void setEntryAndExit(
            StateGraph<AgentState> graph,
            List<WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> edgesBySource) throws Exception {

        Set<String> hasIncoming = new HashSet<>();
        for (List<WorkflowEdge> outEdges : edgesBySource.values()) {
            for (WorkflowEdge edge : outEdges) {
                if (edge.getTarget() != null) {
                    hasIncoming.add(edge.getTarget());
                }
            }
        }

        // 入口（迭代7）：所有「无入边」的节点都接到 START。
        // 修复前只接第一个，多源工作流的其余源节点从 START 不可达、会被静默跳过，
        // 与 DAG 引擎的多源语义不一致。
        List<WorkflowNode> entryNodes = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!hasIncoming.contains(node.getId())) {
                entryNodes.add(node);
            }
        }
        if (entryNodes.isEmpty() && !nodes.isEmpty()) {
            log.warn("未找到入口节点，使用第一个节点作为入口");
            entryNodes.add(nodes.get(0));
        }
        for (WorkflowNode entryNode : entryNodes) {
            log.info("设置入口节点: {}", entryNode.getId());
            graph.addEdge(StateGraph.START, entryNode.getId());
        }

        // 出口：所有终端节点都接到 END
        boolean hasExit = false;
        for (WorkflowNode node : nodes) {
            if (edgesBySource.getOrDefault(node.getId(), List.of()).isEmpty()) {
                log.info("设置出口节点: {}", node.getId());
                graph.addEdge(node.getId(), StateGraph.END);
                hasExit = true;
            }
        }
        if (!hasExit && !nodes.isEmpty()) {
            // 纯环图：每个节点都有出边，没有天然出口，只能依赖循环守卫收敛
            log.warn("工作流没有出口节点（疑似纯环图），执行将依赖循环守卫收敛");
        }
    }

    private CompiledGraph<AgentState> compile(StateGraph<AgentState> graph) throws Exception {
        if (checkpointSaver != null) {
            log.info("装配 CheckpointSaver: {}", checkpointSaver.getClass().getSimpleName());
            return graph.compile(CompileConfig.builder().checkpointSaver(checkpointSaver).build());
        }
        return graph.compile();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 按源节点索引出边（忽略 source 为空的悬空边）。 */
    private Map<String, List<WorkflowEdge>> indexBySource(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<String, List<WorkflowEdge>> result = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            result.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            if (edge.getSource() == null) {
                continue;
            }
            result.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge);
        }
        return result;
    }

    /**
     * 识别成环的「回边目标节点」——即循环入口。
     *
     * <p>判定：边 (source -&gt; target) 若满足「target 能沿其它边回到 source」（含自环），
     * 则 target 处于某个环上，作为循环入口装配迭代守卫。
     * 任何环都至少包含一条这样的回边，因此所有环都会被覆盖。
     */
    private Set<String> detectLoopEntries(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (WorkflowEdge edge : edges) {
            if (edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }
            adjacency.computeIfAbsent(edge.getSource(), key -> new LinkedHashSet<>()).add(edge.getTarget());
        }

        Set<String> loopEntries = new LinkedHashSet<>();
        for (WorkflowEdge edge : edges) {
            if (edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }
            if (canReach(adjacency, edge.getTarget(), edge.getSource())) {
                loopEntries.add(edge.getTarget());
            }
        }

        // 迭代7（评审报告中危项）：同一环只保留一个守卫节点。
        // 修复前每个回边目标都会各自装配 LoopNode 并独立计数（简单环 A↔B 因此得到
        // 两份迭代配额），把 maxIterations 稀释成「每个节点各一份」。
        // 这里用「互相可达」把环上节点聚类，每类只取一个代表——环上任一节点每轮都会
        // 执行，包装其一即可正确计数。
        Set<String> guards = new LinkedHashSet<>();
        Set<String> assigned = new HashSet<>();
        for (String candidate : loopEntries) {
            if (assigned.contains(candidate)) {
                continue;
            }
            guards.add(candidate);
            for (String other : loopEntries) {
                if (!assigned.contains(other)
                        && canReach(adjacency, candidate, other)
                        && canReach(adjacency, other, candidate)) {
                    assigned.add(other);
                }
            }
        }
        return guards;
    }

    /** from 是否能沿有向边到达 to（from == to 时视为可达，即自环）。 */
    private boolean canReach(Map<String, Set<String>> adjacency, String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (next.equals(to)) {
                    return true;
                }
                stack.push(next);
            }
        }
        return false;
    }

    /** 读取节点配置的最大迭代次数，非法值回退缺省值。 */
    private int maxIterationsOf(WorkflowNode node) {
        if (node.getData() == null) {
            return LoopNode.DEFAULT_MAX_ITERATIONS;
        }
        Object raw = node.getData().get(LoopNode.MAX_ITERATIONS_FIELD);
        if (raw == null) {
            return LoopNode.DEFAULT_MAX_ITERATIONS;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw).trim());
            return value > 0 ? value : LoopNode.DEFAULT_MAX_ITERATIONS;
        } catch (NumberFormatException e) {
            log.warn("节点 [{}] 的 maxIterations 配置非法: {}，使用缺省值 {}",
                    node.getId(), raw, LoopNode.DEFAULT_MAX_ITERATIONS);
            return LoopNode.DEFAULT_MAX_ITERATIONS;
        }
    }

    private int maxLoopIterations(List<WorkflowNode> nodes, Set<String> loopEntryNodes) {
        int max = LoopNode.DEFAULT_MAX_ITERATIONS;
        for (WorkflowNode node : nodes) {
            if (loopEntryNodes.contains(node.getId())) {
                max = Math.max(max, maxIterationsOf(node));
            }
        }
        return max;
    }

    /**
     * 全局迭代上限 = max(兜底值, 节点数 * (单环最大迭代 + 2))。
     *
     * <p>langgraph4j 的计数器按「节点执行次数」计量（含 START/END 各一次），
     * 默认 25 会让 24 个节点以上的线性工作流被静默截断；这里按图规模放大，
     * 真正的循环约束由节点级 {@link LoopNode} 精确控制。
     */
    private int globalIterationLimit(int nodeCount, int maxLoopIterations) {
        return Math.max(GLOBAL_ITERATION_FLOOR, nodeCount * (maxLoopIterations + 2) + 8);
    }

    private long countConditionalNodes(List<WorkflowNode> nodes, Map<String, List<WorkflowEdge>> edgesBySource) {
        return nodes.stream()
                .filter(node -> ConditionalRouter.isConditionalNode(node, edgesBySource.getOrDefault(node.getId(), List.of())))
                .count();
    }
}
