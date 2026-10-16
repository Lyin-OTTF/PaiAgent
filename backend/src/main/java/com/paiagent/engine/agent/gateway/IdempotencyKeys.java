package com.paiagent.engine.agent.gateway;

import com.alibaba.fastjson2.JSON;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等键生成（迭代6修复）。
 *
 * <p>旧实现 {@code Integer.toHexString(base.hashCode())} 有三个正确性缺陷：
 * <ul>
 *   <li><b>32 位哈希碰撞</b>：生日悖论下数千个 key 即有可观碰撞率，
 *       碰撞意味着<b>不同入参被判为同一请求</b>——直接返回别的请求的结果，
 *       这是幂等层的正确性 bug，而非理论风险；</li>
 *   <li><b>序列化顺序不稳定</b>：{@code JSON.toJSONString(args)} 依赖 Map 迭代顺序，
 *       不同 Map 实现/插入顺序下同一逻辑入参产生不同 key（漏幂等）；</li>
 *   <li><b>不可审计</b>：ledger 里只存 8 位十六进制，人工无法核对对应入参。</li>
 * </ul>
 *
 * <p>新实现：递归规范化（Map 按键排序、集合保序）→ UTF-8 JSON → SHA-256 → 截断 128 bit，
 * 并保留 {@code tenant:tool:} 可读前缀。碰撞概率从 2^-32 量级降到 2^-128 量级；
 * 相同逻辑入参在任何 Map 实现/顺序下产生相同 key。
 */
public final class IdempotencyKeys {

    /** 摘要长度：128 bit（32 个十六进制字符），抗碰撞且 key 不至于过长。 */
    private static final int DIGEST_HEX_LENGTH = 32;

    private static final String DEFAULT_TENANT = "global";

    private IdempotencyKeys() {
    }

    /**
     * 生成幂等键：{@code tenant:tool:sha256_128(canonicalJson)}。
     *
     * @param tenantId 调用方租户（null 时按 "global" 处理，与旧语义一致）
     * @param toolName 工具名
     * @param args     工具入参（任意嵌套 Map/Collection/标量；null 安全）
     */
    public static String build(String tenantId, String toolName, Map<String, Object> args) {
        String tenant = tenantId == null ? DEFAULT_TENANT : tenantId;
        String base = tenant + ":" + toolName + ":" + JSON.toJSONString(canonicalize(args));
        return tenant + ":" + toolName + ":" + sha256Hex(base);
    }

    /**
     * 递归规范化：Map 转为按键排序的 TreeMap，Collection 保序映射，标量原样返回。
     * 保证同一逻辑入参的序列化结果稳定，与 Map 实现和插入顺序无关。
     */
    static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonicalize(v)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                normalized.add(canonicalize(item));
            }
            return normalized;
        }
        return value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.length() > DIGEST_HEX_LENGTH ? hex.substring(0, DIGEST_HEX_LENGTH) : hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现，运行期不可达；真出现则fail-fast而非静默碰撞
            throw new IllegalStateException("JDK 缺少 SHA-256 实现，无法生成幂等键", e);
        }
    }
}
