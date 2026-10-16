package com.paiagent.common;

import java.util.regex.Pattern;

/**
 * API 密钥脱敏（迭代7 · 评审报告 A3）。
 *
 * <p>配置类接口此前直接返回实体，明文下发 {@code apiKey}；现统一在出站前打码，
 * 只保留可辨识的前后缀，避免密钥经由接口泄露。
 *
 * <p><b>回写保护</b>：前端编辑配置时会原样回传打码值，{@link #isMasked} 用于识别该情形，
 * 由 Service 保留库中原值——既能安全展示，又不会把掩码串写进数据库。
 */
public final class ApiKeyMasker {

    /** 掩码中段（固定长度，不泄露原密钥长度）。 */
    private static final String MASK = "****";

    /** 保留的前缀长度（如 sk-）。 */
    private static final int PREFIX = 3;

    /** 保留的后缀长度。 */
    private static final int SUFFIX = 4;

    /** 严格的掩码形态：{@code xxx****yyyy} 或整串 {@code ****}。 */
    private static final Pattern MASKED_PATTERN = Pattern.compile("^.{" + PREFIX + "}\\*{4}.{" + SUFFIX + "}$|^\\*{4}$");

    private ApiKeyMasker() {
    }

    /**
     * 对密钥打码：{@code sk-abcdefghijklmn} → {@code sk-****klmn}。
     * 过短的密钥整体打码（保留前后缀反而会暴露大部分内容）。
     */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return apiKey;
        }
        String value = apiKey.trim();
        if (value.length() <= PREFIX + SUFFIX) {
            return MASK;
        }
        return value.substring(0, PREFIX) + MASK + value.substring(value.length() - SUFFIX);
    }

    /**
     * 判断值是否为 {@link #mask} 的产物。
     * 用于保存时识别「前端回传的是掩码而非新密钥」，从而保留库中原值。
     */
    public static boolean isMasked(String value) {
        return value != null && MASKED_PATTERN.matcher(value).matches();
    }
}
