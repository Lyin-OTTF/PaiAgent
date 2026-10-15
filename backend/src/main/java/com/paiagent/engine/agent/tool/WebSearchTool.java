package com.paiagent.engine.agent.tool;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import com.paiagent.entity.McpToolConfig;
import com.paiagent.service.McpToolConfigService;
import com.paiagent.service.SearchInfinityMcpClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ToolPolicy(level = AutonomyLevel.OBSERVE)
public class WebSearchTool implements AgentTool {

    private final SearchInfinityMcpClient searchInfinityMcpClient;
    private final McpToolConfigService mcpToolConfigService;

    public WebSearchTool(SearchInfinityMcpClient searchInfinityMcpClient,
                         McpToolConfigService mcpToolConfigService) {
        this.searchInfinityMcpClient = searchInfinityMcpClient;
        this.mcpToolConfigService = mcpToolConfigService;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "通过 Agent Plan Harness 联网搜索 MCP 获取实时、权威信息。";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"number\"}},\"required\":[\"query\"]}";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, AgentToolContext context) throws Exception {
        String query = String.valueOf(arguments.getOrDefault("query", ""));
        int limit = arguments.get("limit") instanceof Number number ? number.intValue() : 5;
        McpToolConfig selectedMcp = mcpToolConfigService.resolveFirstEnabledWebSearch(context.node().getData().get("mcpToolIds"));
        if (selectedMcp != null) {
            Map<String, Object> result = searchInfinityMcpClient.webSearch(selectedMcp, query, limit, null, null, "web");
            return toOutput(result);
        }

        throw new IllegalArgumentException("联网搜索工具未配置，请先在 MCP 工具中添加 Agent Plan 联网搜索，并在当前大模型节点选择它");
    }

    private Map<String, Object> toOutput(Map<String, Object> result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", result.get("summary"));
        output.put("results", result.get("results"));
        output.put("citations", result.get("citations"));
        return output;
    }
}
