package com.paiagent.engine.agent.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迭代6：幂等键生成测试（SHA-256 规范化）。
 *
 * <p>旧实现 {@code Integer.toHexString(hashCode())} 的缺陷回归防护：
 * 不同入参不得碰撞、同一逻辑入参在不同 Map 实现/顺序下必须同 key。
 */
class IdempotencyKeysTest {

    @Test
    @DisplayName("同一逻辑入参：不同 Map 实现与插入顺序产生相同 key")
    void sameLogicalArgsProduceSameKey() {
        Map<String, Object> hashOrder = new HashMap<>();
        hashOrder.put("orderNo", "SO-1001");
        hashOrder.put("page", 1);

        Map<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("page", 1);
        reverseOrder.put("orderNo", "SO-1001");

        Map<String, Object> treeOrder = new TreeMap<>();
        treeOrder.put("page", 1);
        treeOrder.put("orderNo", "SO-1001");

        String k1 = IdempotencyKeys.build("t-1", "queryOrder", hashOrder);
        String k2 = IdempotencyKeys.build("t-1", "queryOrder", reverseOrder);
        String k3 = IdempotencyKeys.build("t-1", "queryOrder", treeOrder);

        assertEquals(k1, k2, "插入顺序不同不应影响幂等键");
        assertEquals(k1, k3, "Map 实现不同不应影响幂等键");
    }

    @Test
    @DisplayName("不同入参产生不同 key（防 32 位哈希碰撞导致错误命中）")
    void differentArgsProduceDifferentKeys() {
        // 历史上 32 位 hashCode 在这类相近入参上存在实际碰撞样本；
        // 现在用 128 bit SHA-256，语义区分度由测试固化
        Map<String, Object> a = Map.of("orderNo", "SO-1001");
        Map<String, Object> b = Map.of("orderNo", "SO-1002");
        Map<String, Object> c = Map.of("orderNo", "SO-1001", "page", 1);

        String ka = IdempotencyKeys.build("t-1", "queryOrder", a);
        String kb = IdempotencyKeys.build("t-1", "queryOrder", b);
        String kc = IdempotencyKeys.build("t-1", "queryOrder", c);

        assertNotEquals(ka, kb);
        assertNotEquals(ka, kc, "入参多一个键必须产生不同 key");
        assertNotEquals(kb, kc);
    }

    @Test
    @DisplayName("租户与工具名参与键构成：跨租户/跨工具不得互撞")
    void tenantAndToolArePartOfKey() {
        Map<String, Object> args = Map.of("id", 7);
        String k1 = IdempotencyKeys.build("t-1", "queryOrder", args);
        String k2 = IdempotencyKeys.build("t-2", "queryOrder", args);
        String k3 = IdempotencyKeys.build("t-1", "queryRefund", args);

        assertNotEquals(k1, k2, "不同租户不得互撞");
        assertNotEquals(k1, k3, "不同工具不得互撞");
    }

    @Test
    @DisplayName("null 租户按 global 处理（与旧语义一致）；null 入参安全")
    void nullTenantFallsBackToGlobalAndNullArgsSafe() {
        Map<String, Object> args = Map.of("id", 1);
        String withNull = IdempotencyKeys.build(null, "queryOrder", args);
        String withGlobal = IdempotencyKeys.build("global", "queryOrder", args);
        assertEquals(withGlobal, withNull, "null 租户应与 global 等价");

        assertDoesNotThrow(() -> IdempotencyKeys.build("t-1", "queryOrder", null));
    }

    @Test
    @DisplayName("key 格式：tenant:tool:32位十六进制摘要")
    void keyFormatIsReadableAndFixedLength() {
        String key = IdempotencyKeys.build("t-9", "createRefund", Map.of("orderNo", "SO-1"));
        String[] parts = key.split(":");
        assertEquals(3, parts.length, "格式应为 tenant:tool:digest");
        assertEquals("t-9", parts[0]);
        assertEquals("createRefund", parts[1]);
        assertTrue(parts[2].matches("[0-9a-f]{32}"), "摘要应为 32 位小写十六进制，实际: " + parts[2]);
    }

    @Test
    @DisplayName("嵌套结构：深层键序打乱不影响键；集合顺序保持敏感")
    void nestedStructuresCanonicalized() {
        Map<String, Object> nested1 = new LinkedHashMap<>();
        nested1.put("filter", new LinkedHashMap<>(Map.of("status", "PAID", "channel", "APP")));
        nested1.put("items", List.of(Map.of("sku", "A"), Map.of("sku", "B")));

        Map<String, Object> nested2 = new LinkedHashMap<>();
        nested2.put("items", new ArrayList<>(List.of(Map.of("sku", "A"), Map.of("sku", "B"))));
        nested2.put("filter", new TreeMap<>(Map.of("channel", "APP", "status", "PAID")));

        assertEquals(
                IdempotencyKeys.build("t-1", "batch", nested1),
                IdempotencyKeys.build("t-1", "batch", nested2),
                "嵌套 Map 键序打乱不应影响幂等键");

        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("filter", Map.of("status", "PAID", "channel", "APP"));
        reordered.put("items", List.of(Map.of("sku", "B"), Map.of("sku", "A")));
        assertNotEquals(
                IdempotencyKeys.build("t-1", "batch", nested1),
                IdempotencyKeys.build("t-1", "batch", reordered),
                "集合元素顺序是语义的一部分，必须区分");
    }
}
