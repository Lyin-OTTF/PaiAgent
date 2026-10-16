package com.paiagent.engine.langgraph.adapter;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.langgraph.NodeExecutionException;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 节点适配器
 * 
 * 将现有的 NodeExecutor 适配为 LangGraph NodeAction
 * 实现节点执行逻辑与 LangGraph 状态模型的桥接
 */
@Slf4j
@Component
public class NodeAdapter {
    
    @Autowired
    private NodeExecutorFactory executorFactory;
    
    /**
     * 将 WorkflowNode 适配为 LangGraph AsyncNodeAction
     * 
     * @param node 工作流节点定义
     * @param eventCallback 事件回调函数（可选）
     * @return LangGraph AsyncNodeAction
     */
    public AsyncNodeAction<AgentState> adaptNode(WorkflowNode node, Consumer<ExecutionEvent> eventCallback) {
        
        return (AgentState state) -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // 触发节点开始事件
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
                }
                
                // 获取节点执行器
                NodeExecutor executor = executorFactory.getExecutor(node.getType());
                
                // 从状态中提取当前输入
                Map<String, Object> stateData = state.data();
                @SuppressWarnings("unchecked")
                Map<String, Object> currentInput = (Map<String, Object>) stateData.getOrDefault("currentInput", new HashMap<>());
                
                // 注入执行身份（迭代6）：节点间 currentInput 会被上游输出整体替换，
                // 因此每步都从状态重新投影，保证 ToolGateway 的审批记录/ActionLedger
                // 都能关联到具体执行记录（HITL 恢复的锚点）。
                currentInput = new HashMap<>(currentInput);
                Object executionId = stateData.get("executionId");
                Object workflowId = stateData.get("workflowId");
                if (executionId != null) {
                    currentInput.put("executionId", executionId);
                }
                if (workflowId != null) {
                    currentInput.put("workflowId", workflowId);
                }
                
                // 为 Output 节点注入 nodeOutputs 以便访问所有节点的输出
                if ("output".equals(node.getType())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Map<String, Object>> nodeOutputs = 
                        (Map<String, Map<String, Object>>) stateData.getOrDefault("nodeOutputs", new HashMap<>());
                    currentInput.put("__nodeOutputs__", nodeOutputs);
                }
                
                // 执行节点
                Map<String, Object> output = executor.execute(node, currentInput, eventCallback);
                
                // 更新状态
                Map<String, Object> newStateData = new HashMap<>(stateData);
                
                // 保存节点输出
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> nodeOutputs =
                    (Map<String, Map<String, Object>>) newStateData.getOrDefault("nodeOutputs", new HashMap<>());
                nodeOutputs.put(node.getId(), output);
                newStateData.put("nodeOutputs", nodeOutputs);
                
                // 记录节点执行状态（保序，供结果提取与断点恢复使用）
                newStateData.put("nodeStatuses", appendNodeStatus(newStateData, node.getId(), "SUCCESS"));
                
                // 更新当前输入为本节点输出（传递给下一个节点）
                newStateData.put("currentInput", output);
                newStateData.put("currentNodeId", node.getId());
                
                // 触发节点成功事件
                if (eventCallback != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    Map<String, Object> eventData = new HashMap<>();
                    eventData.put("input", currentInput);
                    eventData.put("output", output);
                    eventData.put("duration", duration);
                    eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), node.getType(), eventData, (int) duration));
                }
                
                return CompletableFuture.completedFuture(newStateData);
                
            } catch (com.paiagent.engine.langgraph.loop.LoopLimitExceededException e) {
                // 循环超限属于结构性失败：与普通节点失败一致按 fail-stop 处理，
                // 终止图执行并携带状态快照，让失败可观测、部分结果不丢失。
                log.error("循环节点超限，终止图执行: {}", node.getId(), e);
                throw buildNodeExecutionException(state, node, e, eventCallback);
            } catch (Exception e) {
                // fail-stop（迭代6）：与 DAG 引擎语义对齐——节点失败即终止图执行。
                // 旧实现把失败状态写回后返回 completedFuture，langgraph4j 会继续
                // 路由到下游节点（fail-continue），导致失败后的节点照样执行、
                // 消耗外部副作用且结果集语义混乱。现在改为抛出携带快照的异常。
                log.error("节点执行失败，终止图执行: {}", node.getId(), e);
                throw buildNodeExecutionException(state, node, e, eventCallback);
            }
        };
    }
    
    /**
     * 构造节点执行失败异常：发送节点错误事件，在失败时刻的状态快照上标记 FAILED，
     * 并把失败节点记入 nodeStatuses（不进 nodeOutputs——失败节点没有有效输出）。
     */
    private NodeExecutionException buildNodeExecutionException(
            AgentState state, WorkflowNode node, Exception cause, Consumer<ExecutionEvent> eventCallback) {
        
        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), cause.getMessage()));
        }
        
        Map<String, Object> errorState = new HashMap<>(state.data());
        errorState.put("status", "FAILED");
        errorState.put("errorMessage", "节点 " + node.getId() + " 执行失败: " + cause.getMessage());
        errorState.put("currentNodeId", node.getId());
        errorState.put("nodeStatuses", appendNodeStatus(errorState, node.getId(), "FAILED"));
        
        return new NodeExecutionException(
                "节点 " + node.getId() + " 执行失败: " + cause.getMessage(), cause, errorState);
    }
    
    /**
     * 向状态的 nodeStatuses（保插入序=执行序）追加一条节点状态记录。
     *
     * <p>注意：装配 CheckpointSaver 的图执行中，节点间状态会经 JSON 往返
     * （fastjson 反序列化为 JSONObject），因此不能用 {@code instanceof LinkedHashMap}
     * 判定类型——任何 {@code Map} 实例（含 JSONObject）都应拷贝保留历史。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> appendNodeStatus(Map<String, Object> stateData, String nodeId, String status) {
        Object existing = stateData.get("nodeStatuses");
        Map<String, Object> statuses = (existing instanceof Map)
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        statuses.put(nodeId, status);
        return statuses;
    }
    
    /**
     * 批量适配多个节点
     * 
     * @param nodes 节点列表
     * @param eventCallback 事件回调
     * @return 节点ID到AsyncNodeAction的映射
     */
    public Map<String, AsyncNodeAction<AgentState>> adaptNodes(
            java.util.List<WorkflowNode> nodes, 
            Consumer<ExecutionEvent> eventCallback) {
        
        Map<String, AsyncNodeAction<AgentState>> adaptedNodes = new HashMap<>();
        
        for (WorkflowNode node : nodes) {
            adaptedNodes.put(node.getId(), adaptNode(node, eventCallback));
        }
        
        return adaptedNodes;
    }
}
