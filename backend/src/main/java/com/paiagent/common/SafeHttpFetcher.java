package com.paiagent.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 出站 HTTP 文本抓取的统一安全实现（迭代7：评审报告 A4 边界闭合）。
 *
 * <p>背景：SSRF 防护曾在 {@code WebFetchNodeExecutor} 与 {@code WebFetchTool} 各写一份，
 * 修复时只补了前者，遗留后者仍可被诱导访问内网/云元数据。此工具类作为<b>唯一实现</b>，
 * 所有抓取入口复用，杜绝「改一处漏一处」。
 *
 * <p>安全要点：
 * <ul>
 *   <li>每跳都经 {@link UrlSafetyValidator#assertSafeOutbound(String)} 校验（协议白名单 + 内网/环回/链路本地/保留地址黑名单 + 拒绝内嵌凭证）；</li>
 *   <li>手动跟随重定向并逐跳复检——自动跟随可被「公网 302 → 内网」绕过起始校验；</li>
 *   <li>连接与读取超时；响应体限长读取，避免超大响应 OOM。</li>
 * </ul>
 *
 * <p><b>残余风险（如实标注）</b>：校验与连接之间的 DNS 重绑定窗口无法在本技术栈内彻底关闭
 * （{@code HttpURLConnection} 不允许覆盖 Host 头，直连 IP 会破坏虚拟主机与 TLS 校验），
 * 详见 {@link UrlSafetyValidator} 类注释。要彻底闭合需出口代理层方案。
 */
public final class SafeHttpFetcher {

    /** 最大重定向跳数（每一跳都重新做安全校验）。 */
    public static final int MAX_REDIRECTS = 5;

    private SafeHttpFetcher() {
    }

    /**
     * 抓取单个 URL 的文本内容。
     *
     * @param urlText         目标 URL
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读取超时（毫秒）
     * @param maxBytes         响应体读取上限（字节）
     * @return UTF-8 解码后的正文（最多 {@code maxBytes} 字节）
     * @throws IOException 网络异常、重定向缺失 Location、超过跳数上限或读取失败
     */
    public static String fetchText(String urlText, int connectTimeoutMs, int readTimeoutMs, int maxBytes)
            throws IOException {
        String current = urlText;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            UrlSafetyValidator.assertSafeOutbound(current);
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            try {
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "PaiAgent/1.0");

                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("重定向缺少 Location 头: " + current);
                    }
                    // 相对 Location 按当前 URL 解析；下一轮循环会重新做安全校验
                    current = URI.create(current).resolve(location.trim()).toString();
                    continue;
                }

                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) {
                    throw new IOException("HTTP " + status + ": " + current);
                }
                try (InputStream body = stream) {
                    return readLimited(body, maxBytes);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("重定向次数超过上限（" + MAX_REDIRECTS + "）: " + urlText);
    }

    /** 限长读取：最多 {@code maxBytes} 字节，避免无上限读入导致内存膨胀。 */
    private static String readLimited(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            int remaining = maxBytes - buffer.size();
            if (remaining <= 0) {
                break;
            }
            buffer.write(chunk, 0, Math.min(read, remaining));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
