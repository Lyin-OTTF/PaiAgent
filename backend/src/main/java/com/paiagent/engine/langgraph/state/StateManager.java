package com.paiagent.engine.langgraph.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态管理器
 * 
 * 负责 WorkflowState 的初始化、更新和提取
 * 提供与 LangGraph 状态模型的转换接口
 */
@Slf4j
@Component
public class StateManager {
    
    /**
     * 初始化工作流状态
     * 
     * @param inputData 原始输入数据
     * @return LangGraph 状态 Map
     */
    public Map<String, Object> initializeState(String inputData) {
        return initializeState(inputData, null, null);
    }

    /**
     * 初始化状态（携带执行身份）。
     *
     * <p>迭代6：executionId/workflowId 随状态下沉到每个节点输入，
     * 使 ToolGateway 创建的审批记录与 ActionLedger 能关联到具体执行
     * （修复前 ctx.executionId() 恒为 null，审批记录无法回溯执行，HITL 恢复无锚点）。
     * 断点恢复时状态来自检查点 JSON，老检查点无这两个键，注入侧对 null 安全。
     */
    public Map<String, Object> initializeState(String inputData, Long executionId, Long workflowId) {
        Map<String, Object> state = new HashMap<>();
        
        // 设置原始输入
        state.put("inputData", inputData);

        // 执行身份（供节点内 ToolExecutionContext 使用）
        if (executionId != null) {
            state.put("executionId", executionId);
        }
        if (workflowId != null) {
            state.put("workflowId", workflowId);
        }
        
        // 初始化当前输入（用于节点间传递）
        Map<String, Object> currentInput = new HashMap<>();
        currentInput.put("input", inputData);
        if (executionId != null) {
            currentInput.put("executionId", executionId);
        }
        if (workflowId != null) {
            currentInput.put("workflowId", workflowId);
        }
        state.put("currentInput", currentInput);
        
        // 初始化节点输出映射
        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        state.put("nodeOutputs", nodeOutputs);
        
        // 初始化节点执行状态（保序：插入序即执行序，结果提取与断点恢复共用）
        Map<String, Object> nodeStatuses = new LinkedHashMap<>();
        state.put("nodeStatuses", nodeStatuses);
        
        // 初始化状态
        state.put("status", "RUNNING");
        state.put("startTime", System.currentTimeMillis());
        
        log.info("初始化工作流状态: inputData={}", inputData);
        return state;
    }
    
    /**
     * 获取最终输出数据
     * 
     * @param state LangGraph 状态
     * @return 最终输出数据（JSON 字符串）
     */
    public Map<String, Object> getFinalOutput(Map<String, Object> state) {
        // 获取当前输入（最后一个节点的输出）
        @SuppressWarnings("unchecked")
        Map<String, Object> currentInput = 
            (Map<String, Object>) state.getOrDefault("currentInput", new HashMap<>());
        
        return currentInput;
    }
    
    /**
     * 检查工作流是否执行成功
     * 
     * <p>迭代6修复：状态机三态完备化（RUNNING 执行中 → SUCCESS/FAILED 终态）。
     * 旧实现把 RUNNING 也判为成功，是因为成功路径从不写 SUCCESS 终态而打的补丁，
     * 导致「图是否真的跑完」无法从状态区分。现在 {@code isSuccessful} 只认显式
     * 的 SUCCESS 终态，RUNNING（含缺省）一律判失败——由图执行出口
     * {@link #markSuccess} 负责写入终态。
     * 
     * @param state LangGraph 状态
     * @return true 表示已到达 SUCCESS 终态
     */
    public boolean isSuccessful(Map<String, Object> state) {
        String status = (String) state.getOrDefault("status", "RUNNING");
        return "SUCCESS".equals(status);
    }
    
    /**
     * 标记工作流到达成功终态（迭代6）。
     * 
     * <p>由图执行出口调用：LangGraph 的 invoke 正常返回意味着所有已路由节点
     * 执行完毕且无节点失败（失败会以 {@link NodeExecutionException} 抛出），
     * 此时把状态从 RUNNING 推进为 SUCCESS，使状态机名副其实。
     * 
     * <p>注意：langgraph4j 的 {@code AgentState.data()} 可能返回不可变 Map，
     * 因此这里返回拷贝后的新 Map，由调用方替换使用。
     * 
     * @param state LangGraph 状态
     * @return 置为 SUCCESS 终态后的新状态 Map
     */
    public Map<String, Object> markSuccess(Map<String, Object> state) {
        Map<String, Object> finalized = new HashMap<>(state);
        finalized.put("status", "SUCCESS");
        log.info("工作流执行成功，状态推进为 SUCCESS");
        return finalized;
    }
    
    /**
     * 获取错误信息
     * 
     * @param state LangGraph 状态
     * @return 错误信息，如果没有则返回 null
     */
    public String getErrorMessage(Map<String, Object> state) {
        return (String) state.get("errorMessage");
    }
    
    /**
     * 从状态中提取节点执行结果列表
     * 
     * <p>迭代6修复：旧实现只遍历 nodeOutputs（仅含成功节点）并硬编码 status=SUCCESS，
     * 失败节点与未启动节点都无法在结果中体现。现以 {@code nodeStatuses}
     * （保序，插入序=执行序）为基准：
     * <ul>
     *   <li>SUCCESS 节点：output 取自 nodeOutputs；</li>
     *   <li>FAILED 节点：output 为 null，error 取自 errorMessage（与 DAG 引擎一致，
     *       失败节点以 FAILED 状态进入结果）；</li>
     *   <li>未启动节点：nodeStatuses 中不存在，不出现在结果里。</li>
     * </ul>
     * 
     * @param state LangGraph 状态
     * @param config 工作流配置（用于获取节点名称）
     * @return 节点执行结果列表
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> extractNodeResults(
            Map<String, Object> state, 
            com.paiagent.engine.model.WorkflowConfig config) {
        
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        
        // 获取节点执行状态（保序基准）
        Map<String, Object> nodeStatuses = 
            (Map<String, Object>) state.getOrDefault("nodeStatuses", new LinkedHashMap<>());
        
        if (nodeStatuses.isEmpty()) {
            return results;
        }
        
        // 获取节点输出（仅成功节点有）
        Map<String, Map<String, Object>> nodeOutputs = 
            (Map<String, Map<String, Object>>) state.getOrDefault("nodeOutputs", new HashMap<>());
        
        // 节点 ID → 节点类型（迭代7：原变量名 nodeIdToName 实际存的是 type，命名误导）
        Map<String, String> nodeIdToType = new HashMap<>();
        for (var node : config.getNodes()) {
            nodeIdToType.put(node.getId(), node.getType());
        }
        
        String errorMessage = (String) state.get("errorMessage");
        
        // 转换为 NodeResult 格式（按执行序）
        for (Map.Entry<String, Object> entry : nodeStatuses.entrySet()) {
            String nodeId = entry.getKey();
            String nodeStatus = String.valueOf(entry.getValue());
            Map<String, Object> output = nodeOutputs.get(nodeId);
            
            Map<String, Object> nodeResult = new HashMap<>();
            nodeResult.put("nodeId", nodeId);
            nodeResult.put("nodeName", nodeIdToType.getOrDefault(nodeId, nodeId));
            nodeResult.put("status", nodeStatus);
            if ("FAILED".equals(nodeStatus)) {
                nodeResult.put("output", (String) null);
                nodeResult.put("error", errorMessage);
            } else {
                nodeResult.put("output", com.alibaba.fastjson2.JSON.toJSONString(output));
            }
            
            results.add(nodeResult);
        }
        
        return results;
    }
}
