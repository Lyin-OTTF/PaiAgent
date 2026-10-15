package com.paiagent.engine.agent.tool;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets the agent inspect the input currently passed to the node.
 */
@Component
@ToolPolicy(level = AutonomyLevel.OBSERVE)
public class ReadCurrentInputTool implements AgentTool {

    @Override
    public String getName() {
        return "read_current_input";
    }

    @Override
    public String getDescription() {
        return "读取当前 ReAct 节点收到的输入数据。适合在需要确认上游节点输出结构时调用。";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, AgentToolContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentInput", context.currentInput());
        return result;
    }
}
