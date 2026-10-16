package com.paiagent.engine.agent.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等守卫（Redis 共享 + 进程内回退，迭代1 遗留治理）。
 * 对同一 {工具名 + 归一化入参} 在 TTL 窗口内的重复调用，返回首次结果，避免重复副作用。
 *
 * <p><b>两级存储</b>：
 * <ul>
 *   <li>Redis 为主：{@code SET key value PX ttl}，跨进程/多实例共享，多实例部署下幂等真正生效；</li>
 *   <li>进程内 Map 为回退：Redis 故障（连接超时/宕机）时自动降级为原内存实现，
 *       单实例部署行为与治理前完全一致，绝不让缓存故障放大成工具执行故障。</li>
 * </ul>
 *
 * <p><b>读写路径</b>：{@link #getIfPresent} 先查 Redis、未命中再查内存（回退路径写入的）；
 * 执行前用 {@link #tryClaim} 原子占位（迭代9 P1-8 消除 check-then-act 竞态），
 * {@link #put} 双写（内存 + Redis）落结果，失败路径用 {@link #release} 归还占位。
 * 任一侧异常只打 WARN 不影响主流程。
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** Redis key 前缀（paiagent:idem: + 调用方传入的幂等键）。 */
    private static final String REDIS_KEY_PREFIX = "paiagent:idem:";

    /**
     * 在途占位标记（迭代9 P1-8）。
     *
     * <p>写入 Redis 的是一个<b>非 JSON 串</b>：读取侧 {@link #parseJson} 解析失败会按
     * 「未命中」处理，天然不会把占位符当成真实结果返回，无需额外的类型判别。
     */
    private static final String IN_FLIGHT_MARKER = "__paiagent_idem_in_flight__";

    private final long ttlMillis;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public IdempotencyGuard(@Value("${paiagent.agent.idempotency-ttl-ms:600000}") long ttlMillis,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper) {
        this.ttlMillis = ttlMillis;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 若 key 在 TTL 内已存在（Redis 或内存），返回首次结果；否则返回 null。 */
    public Map<String, Object> getIfPresent(String key) {
        // 1. Redis 共享层（多实例下跨进程命中）
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + key);
            if (json != null) {
                Map<String, Object> parsed = parseJson(json);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            // Redis 故障降级到内存层：单实例行为不变，多实例退化为进程内幂等
            log.warn("读取 Redis 幂等缓存失败，降级为进程内查询: key={}, error={}", key, e.getMessage());
        }

        // 2. 进程内回退层（Redis 未命中或不可用）
        Entry entry = store.get(key);
        if (entry == null || entry.value == null) {
            // 值为 null = 在途占位（已抢占、尚未产出结果）：不算命中，交由调用方继续
            return null;
        }
        if (System.currentTimeMillis() - entry.ts > ttlMillis) {
            store.remove(key);
            return null;
        }
        return entry.value;
    }

    /**
     * 原子抢占幂等键（迭代9 P1-8）。
     *
     * <p>修复前网关侧是「{@link #getIfPresent} 未命中 → 执行工具 → {@link #put}」的
     * check-then-act：两个并发请求会同时未命中、各执行一次带副作用的工具，幂等被架空。
     * 现在执行前先原子占位（进程内 {@code putIfAbsent} + Redis {@code SETNX}），
     * 只有抢到执行权的调用才继续；其余调用视为「有并发在途」被拒。
     *
     * <p><b>判定优先级</b>：
     * <ol>
     *   <li>Redis 明确返回 {@code true}（本实例抢到）→ 放行；</li>
     *   <li>Redis 明确返回 {@code false}（<b>他人</b>持有，含其它实例）→ 拒绝，
     *       并撤销本地占位。否则「内存抢到但 Redis 被占」会退化成放行，
     *       {@code SETNX} 对跨实例幂等就完全失去意义；</li>
     *   <li>Redis 抛异常（不可用）→ 降级，以进程内占位结果为准（单实例幂等仍生效）。</li>
     * </ol>
     *
     * @return {@code true} 抢到执行权，应继续执行；{@code false} 已被其它在途/已完成调用占有
     */
    public boolean tryClaim(String key) {
        Entry claimedEntry = new Entry(System.currentTimeMillis(), null);
        boolean memoryClaimed = store.putIfAbsent(key, claimedEntry) == null;

        Boolean redisAcquired = null;
        try {
            redisAcquired = redisTemplate.opsForValue()
                    .setIfAbsent(REDIS_KEY_PREFIX + key, IN_FLIGHT_MARKER, Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            // Redis 故障：降级为进程内幂等（与 get/put 的降级策略一致）
            log.warn("Redis 幂等占位失败，降级为进程内占位: key={}, error={}", key, e.getMessage());
        }

        if (redisAcquired == null) {
            // Redis 不可用：以本地抢占为准（单实例语义与治理前一致）
            return memoryClaimed;
        }
        if (Boolean.TRUE.equals(redisAcquired)) {
            return true;
        }
        // Redis 明确拒绝：键已被他人（其它实例）持有 → 拒绝重复执行，并撤销本地占位保持两层一致
        if (memoryClaimed) {
            store.remove(key, claimedEntry);
        }
        return false;
    }

    /** 释放占位（执行失败时调用，避免 TTL 窗口内正常重试被误拒）。 */
    public void release(String key) {
        Entry entry = store.get(key);
        if (entry != null && entry.value == null) {
            store.remove(key, entry);
        }
        try {
            String current = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + key);
            if (IN_FLIGHT_MARKER.equals(current)) {
                redisTemplate.delete(REDIS_KEY_PREFIX + key);
            }
        } catch (Exception e) {
            log.warn("释放 Redis 幂等占位失败: key={}, error={}", key, e.getMessage());
        }
    }

    public void put(String key, Map<String, Object> value) {
        store.put(key, new Entry(System.currentTimeMillis(), value));
        try {
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX + key,
                    objectMapper.writeValueAsString(value),
                    Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            // 写 Redis 失败不影响内存层，下个 TTL 窗口内本实例仍可命中
            log.warn("写入 Redis 幂等缓存失败，仅保留进程内缓存: key={}, error={}", key, e.getMessage());
        }
    }

    public void clear() {
        store.clear();
        try {
            // clear 仅用于测试/运维清理；keys 模式匹配只在此低频路径使用
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清理 Redis 幂等缓存失败: error={}", e.getMessage());
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("反序列化 Redis 幂等缓存失败，按未命中处理: error={}", e.getMessage());
            return null;
        }
    }

    private record Entry(long ts, Map<String, Object> value) {
    }
}
