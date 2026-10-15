package com.paiagent.engine.agent.policy;

import com.paiagent.engine.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具治理策略注册表。
 * 启动时扫描所有 {@link AgentTool} Spring Bean，读取其 {@link ToolPolicy} 注解，
 * 构建「工具名 → 解析后策略」的映射，供 {@code ToolGateway} 做执行前决策。
 */
@Component
public class PolicyRegistry {

    private final Map<String, ResolvedPolicy> policyByTool = new LinkedHashMap<>();
    private final Map<String, AgentTool> toolByName = new LinkedHashMap<>();

    public PolicyRegistry(List<AgentTool> tools) {
        for (AgentTool tool : tools) {
            toolByName.put(tool.getName(), tool);
            ToolPolicy annotation = tool.getClass().isAnnotationPresent(ToolPolicy.class)
                    ? tool.getClass().getAnnotation(ToolPolicy.class)
                    : null;
            policyByTool.put(tool.getName(), ResolvedPolicy.fromAnnotation(annotation));
        }
    }

    /** 解析工具策略；未声明注解时返回默认策略（OBSERVE/只读）。 */
    public ResolvedPolicy resolve(String toolName) {
        ResolvedPolicy policy = policyByTool.get(toolName);
        return policy != null ? policy : ResolvedPolicy.defaults();
    }

    public boolean isRegistered(String toolName) {
        return toolByName.containsKey(toolName);
    }

    public AgentTool getTool(String toolName) {
        return toolByName.get(toolName);
    }

    public List<AgentTool> allTools() {
        return new ArrayList<>(toolByName.values());
    }

    /** 暴露「工具名 → 解析后策略」映射（供治理接口展示当前权限分级）。 */
    public Map<String, ResolvedPolicy> allPolicies() {
        return new LinkedHashMap<>(policyByTool);
    }
}
