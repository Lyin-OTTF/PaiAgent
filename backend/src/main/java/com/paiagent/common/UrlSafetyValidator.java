package com.paiagent.common;

import java.net.InetAddress;
import java.net.URI;

/**
 * 出站 URL 安全校验（迭代7 · 评审报告 A4）。
 *
 * <p>修复前 WebFetch 节点与 MinIO 转存直接对「用户/模型可控的 URL」调用
 * {@code openStream()}：无协议白名单、无内网地址校验，可被诱导请求云元数据
 * （169.254.169.254）、回环、私网地址或本地文件（file:），构成 SSRF 原语。
 *
 * <p>本校验器做三件事：
 * <ol>
 *   <li>只放行 {@code http/https}（阻断 file:/jar:/ftp: 等）；</li>
 *   <li>解析主机并拒绝回环 / 私网 / 链路本地 / 运营商级 NAT / 组播 / 保留地址；</li>
 *   <li>主机解析失败一律拒绝——不做「解析不了就放行」的宽松兜底。</li>
 * </ol>
 *
 * <p><b>残余风险（如实标注）</b>：这是「发起请求前」的校验，本身无法防御
 * <b>DNS 重绑定</b>（校验时解析为公网、真正连接时解析为内网）。彻底防御需固定
 * 解析结果后直连 IP 并复核 Host 头，属更重的改造。因此调用方还应配合
 * 「不自动跟随重定向 + 每一跳重新校验」，把绕过面压到最小
 * （见 {@code WebFetchNodeExecutor} 的实现）。
 */
public final class UrlSafetyValidator {

    /** 允许的协议白名单。 */
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    private UrlSafetyValidator() {
    }

    /**
     * 校验 URL 是否可安全出站。
     *
     * @param url 待校验 URL
     * @throws IllegalArgumentException URL 非法、协议不允许或指向内网/保留地址
     */
    public static void assertSafeOutbound(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式非法: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(SCHEME_HTTP.equalsIgnoreCase(scheme) || SCHEME_HTTPS.equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅允许 http/https 协议，已拒绝: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        assertPublicHost(host);
    }

    /** 解析主机并拒绝内网/保留地址；解析失败同样拒绝。 */
    private static void assertPublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析主机名: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new IllegalArgumentException(
                        "拒绝访问内网/保留地址: " + host + " -> " + address.getHostAddress());
            }
        }
    }

    /** 是否为需要阻断的地址（回环/私网/链路本地/组播/保留）。 */
    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isCarrierGradeNat(address)
                || isReservedIpv4(address);
    }

    /** IPv6 唯一本地地址 {@code fc00::/7}。 */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** IPv4 运营商级 NAT {@code 100.64.0.0/10}（云内网常见段）。 */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    /** IPv4 保留段 {@code 240.0.0.0/4}（含 255.255.255.255 广播）。 */
    private static boolean isReservedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xF0) == 0xF0;
    }
}
