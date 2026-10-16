package com.paiagent.engine.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotencyGuard 单元测试（Mockito，无真实 Redis）。
 *
 * 覆盖两级存储语义：
 * 1. 进程内回退层：命中/过期/清理（Redis mock 默认未命中，走内存路径）；
 * 2. Redis 共享层：跨进程命中、put 双写带 TTL、Redis 故障降级不阻断。
 */
class IdempotencyGuardTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;

    /** 模拟 Redis 键空间：使 SETNX / GET / DELETE 语义真实可测（含在途标记的读写与释放）。 */
    private final Map<String, String> redisStore = new HashMap<>();

    private IdempotencyGuard createGuard(long ttlMillis) {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = new ObjectMapper();
        redisStore.clear();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 模拟「健康但为空」的 Redis。注意：Mockito 对返回 Boolean 的方法默认给 false（而非 null），
        // 若不显式模拟 SETNX 语义，「Redis 明确拒绝」与「Redis 不可用」将无法区分。
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> redisStore.putIfAbsent(
                        inv.getArgument(0, String.class), inv.getArgument(1, String.class)) == null);
        when(valueOperations.get(anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        when(redisTemplate.delete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.getArgument(0, String.class)) != null);
        return new IdempotencyGuard(ttlMillis, redisTemplate, objectMapper);
    }

    @Test
    void shouldReturnNullForAbsentKey() {
        IdempotencyGuard guard = createGuard(600000);
        assertNull(guard.getIfPresent("missing"));
    }

    @Test
    void shouldStoreAndReturnSameValue() {
        IdempotencyGuard guard = createGuard(600000);
        Map<String, Object> value = Map.of("k", "v");
        guard.put("key1", value);
        // Redis mock 未命中 → 回退内存层，返回同一引用（与治理前行为一致）
        assertSame(value, guard.getIfPresent("key1"));
    }

    @Test
    void shouldExpireAfterTtl() throws InterruptedException {
        // TTL 取 200ms：put 现含 JSON 序列化 + Redis 写（含首次 Jackson 初始化），
        // 时钟需容让真实 I/O 开销；原 20ms 极值会把 put 自身耗时算进过期窗口
        IdempotencyGuard guard = createGuard(200);
        guard.put("key2", Map.of("x", 1));
        assertNotNull(guard.getIfPresent("key2"));
        Thread.sleep(250);
        assertNull(guard.getIfPresent("key2"));
    }

    @Test
    void shouldClearAll() {
        IdempotencyGuard guard = createGuard(600000);
        when(redisTemplate.keys("paiagent:idem:*")).thenReturn(Set.of());
        guard.put("a", Map.of("x", 1));
        guard.put("b", Map.of("y", 2));
        guard.clear();
        assertNull(guard.getIfPresent("a"));
        assertNull(guard.getIfPresent("b"));
    }

    // ------------------------------------------------------------------
    // Redis 共享层语义

    @Test
    void shouldReturnValueFromRedisAcrossProcesses() throws Exception {
        IdempotencyGuard guard = createGuard(600000);
        // 模拟另一实例已写入 Redis 的缓存值（本进程内存没有）
        when(valueOperations.get("paiagent:idem:cross")).thenReturn("{\"result\":\"from-other-instance\"}");

        Map<String, Object> hit = guard.getIfPresent("cross");

        assertNotNull(hit);
        assertEquals("from-other-instance", hit.get("result"));
    }

    @Test
    void shouldWriteToRedisWithTtlOnPut() throws Exception {
        IdempotencyGuard guard = createGuard(600000);
        guard.put("key3", Map.of("a", 1));

        // put 双写：Redis 侧必须带 TTL（与内存 TTL 对齐），值为 JSON
        verify(valueOperations).set(
                eq("paiagent:idem:key3"),
                eq("{\"a\":1}"),
                any(Duration.class));
        verify(valueOperations).set(
                eq("paiagent:idem:key3"),
                eq("{\"a\":1}"),
                argThat(d -> d != null && Math.abs(d.toMillis() - 600000) < 1000));
    }

    @Test
    void shouldFallBackToMemoryWhenRedisUnavailable() throws Exception {
        IdempotencyGuard guard = createGuard(600000);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));
        // set 是 void 方法，必须用 doThrow 形式打桩
        doThrow(new RuntimeException("redis down")).when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        // Redis 全程故障：put/get 仍走内存层，不抛异常
        Map<String, Object> value = Map.of("k", "v");
        assertDoesNotThrow(() -> guard.put("key4", value));
        assertSame(value, guard.getIfPresent("key4"));
    }

    @Test
    void shouldTreatCorruptRedisValueAsMiss() {
        IdempotencyGuard guard = createGuard(600000);
        // Redis 里是坏 JSON：按未命中处理，不抛异常
        when(valueOperations.get("paiagent:idem:bad")).thenReturn("{not-json");
        assertNull(guard.getIfPresent("bad"));
    }

    @Test
    void shouldNotThrowWhenRedisDeleteFailsOnClear() {
        IdempotencyGuard guard = createGuard(600000);
        when(redisTemplate.keys("paiagent:idem:*")).thenThrow(new RuntimeException("redis down"));
        guard.put("a", Map.of("x", 1));
        // clear 时 Redis 故障只打 WARN，不抛异常
        assertDoesNotThrow(guard::clear);
        assertNull(guard.getIfPresent("a"));
    }

    @Test
    void shouldDeleteRedisKeysOnClear() {
        IdempotencyGuard guard = createGuard(600000);
        when(redisTemplate.keys("paiagent:idem:*")).thenReturn(Set.of("paiagent:idem:a", "paiagent:idem:b"));
        guard.put("a", Map.of("x", 1));
        guard.clear();
        verify(redisTemplate).delete(Set.of("paiagent:idem:a", "paiagent:idem:b"));
    }

    // ------------------------------------------------------------------
    // 迭代9 P1-8：原子占位（tryClaim）与归还（release）

    @Test
    void tryClaimShouldSucceedFirstThenRejectSecond() {
        IdempotencyGuard guard = createGuard(600000);
        assertTrue(guard.tryClaim("claim-1"), "首次占位应成功");
        assertFalse(guard.tryClaim("claim-1"), "同一键第二次占位必须失败（有并发在途）");
    }

    @Test
    void tryClaimShouldNotBeVisibleAsCachedResult() {
        IdempotencyGuard guard = createGuard(600000);
        assertTrue(guard.tryClaim("claim-2"));
        // 在途占位（value=null）不是已产出结果；否则抢占者会拿到空结果提前返回
        assertNull(guard.getIfPresent("claim-2"));
    }

    @Test
    void putAfterClaimShouldExposeResult() {
        IdempotencyGuard guard = createGuard(600000);
        assertTrue(guard.tryClaim("claim-3"));
        guard.put("claim-3", Map.of("r", 1));
        assertEquals(1, guard.getIfPresent("claim-3").get("r"));
    }

    @Test
    void releaseShouldClearClaimSoRetryCanProceed() {
        IdempotencyGuard guard = createGuard(600000);
        assertTrue(guard.tryClaim("claim-4"));
        guard.release("claim-4");
        assertTrue(guard.tryClaim("claim-4"), "释放后应可重新占位（失败重试路径）");
    }

    @Test
    void releaseShouldNotEvictCompletedResult() {
        IdempotencyGuard guard = createGuard(600000);
        assertTrue(guard.tryClaim("claim-5"));
        guard.put("claim-5", Map.of("r", 2));
        guard.release("claim-5");
        // 已产出结果的键不得被 release 清除，否则幂等失效
        assertEquals(2, guard.getIfPresent("claim-5").get("r"));
    }

    @Test
    void tryClaimShouldWriteRedisInFlightMarkerWithTtl() {
        IdempotencyGuard guard = createGuard(600000);
        when(valueOperations.setIfAbsent(eq("paiagent:idem:redis-claim"), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertTrue(guard.tryClaim("redis-claim"));

        // 跨进程占位：Redis 侧 SETNX + TTL，值为在途标记（非 JSON，读取侧天然按未命中处理）
        verify(valueOperations).setIfAbsent(
                eq("paiagent:idem:redis-claim"),
                eq("__paiagent_idem_in_flight__"),
                argThat(d -> d != null && Math.abs(d.toMillis() - 600000) < 1000));
    }

    @Test
    void tryClaimShouldFallBackToMemoryWhenRedisUnavailable() {
        IdempotencyGuard guard = createGuard(600000);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        // Redis 故障时仍须以进程内占位保证单实例幂等
        assertTrue(guard.tryClaim("fallback-1"));
        assertFalse(guard.tryClaim("fallback-1"));
    }

    @Test
    void tryClaimShouldRespectRedisOwnershipAcrossProcesses() {
        IdempotencyGuard guard = createGuard(600000);
        // 另一实例已持有该键（SETNX 返回 false），且本进程内存也未曾见过
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(guard.tryClaim("cross-claim"), "跨实例已被占有时本实例不得重复执行");
    }
}
