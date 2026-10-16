package com.paiagent.engine.agent.hitl;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迭代6：HITL 挂起上下文快照的序列化契约测试。
 * 恢复链路依赖「执行器写入的 Map → JSON 落库 → 还原为 record」不断链，
 * 这里对键名、枚举、嵌套结构做完整往返验证。
 */
class HitlSuspendContextTest {

    private HitlSuspendContext sample() {
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("step", 1);
        item.put("action", "tool_call");
        item.put("toolName", "write_tool");
        Map<String, Object> toolInput = new LinkedHashMap<>();
        toolInput.put("title", "周报");
        toolInput.put("count", 2);
        item.put("toolInput", toolInput);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "PENDING_APPROVAL");
        pending.put("approvalId", 42);
        item.put("observation", pending);
        trace.add(item);

        return new HitlSuspendContext(
                100L, 200L, "react-1", "tenantA", AutonomyLevel.EXECUTE, "react_agent",
                "write_tool", toolInput, "请汇总本周数据",
                new LinkedHashMap<>(Map.of("input", "跑一下周报", "executionId", 100)),
                trace, 2, 5);
    }

    @Test
    @DisplayName("Map → JSON → record 往返保持全部字段（含枚举/嵌套/数字）")
    void roundTripThroughJsonKeepsAllFields() {
        HitlSuspendContext ctx = sample();
        String json = JSON.toJSONString(ctx.toContextMap());

        HitlSuspendContext restored = HitlSuspendContext.fromJson(json);

        assertEquals(ctx, restored);
        assertEquals(AutonomyLevel.EXECUTE, restored.allowedLevel());
        assertEquals(2, restored.step());
        assertEquals(5, restored.maxSteps());
        // 嵌套 toolInput / trace 结构不丢
        assertEquals("周报", restored.toolInput().get("title"));
        assertEquals(1, restored.trace().size());
        assertEquals("PENDING_APPROVAL",
                ((Map<?, ?>) restored.trace().get(0).get("observation")).get("status"));
    }

    @Test
    @DisplayName("toContextMap 键名与记录组件一一对应（落库/还原的契约）")
    void contextMapKeysMatchRecordComponents() {
        Map<String, Object> map = sample().toContextMap();
        assertTrue(map.containsKey("executionId"));
        assertTrue(map.containsKey("workflowId"));
        assertTrue(map.containsKey("nodeId"));
        assertTrue(map.containsKey("tenantId"));
        assertTrue(map.containsKey("allowedLevel"));
        assertEquals("EXECUTE", map.get("allowedLevel"));
        assertTrue(map.containsKey("caller"));
        assertTrue(map.containsKey("toolName"));
        assertTrue(map.containsKey("toolInput"));
        assertTrue(map.containsKey("goalPrompt"));
        assertTrue(map.containsKey("input"));
        assertTrue(map.containsKey("trace"));
        assertTrue(map.containsKey("step"));
        assertTrue(map.containsKey("maxSteps"));
    }

    @Test
    @DisplayName("allowedLevel 为 null 时序列化为 null（不抛异常）")
    void nullAllowedLevelSerializesAsNull() {
        HitlSuspendContext ctx = new HitlSuspendContext(
                1L, 2L, "n", "global", null, "react_agent",
                "t", Map.of(), "goal", Map.of(), List.of(), 1, 3);
        Map<String, Object> map = ctx.toContextMap();
        assertNull(map.get("allowedLevel"));
        assertDoesNotThrow(() -> HitlSuspendContext.fromJson(JSON.toJSONString(map)));
    }
}
