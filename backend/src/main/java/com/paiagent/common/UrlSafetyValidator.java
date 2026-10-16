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
 * <p><b>残余风险（迭代8 已实证并如实标注）</b>：
 * <ul>
 *   <li><b>DNS 重绑定窗口</b>：本校验在「发起请求前」解析一次 DNS，而连接时会再次解析，
 *   两次解析之间（微秒~毫秒级）攻击者可借 TTL=0 的重绑定域名把连接导向内网。
 *   「彻底防御需固定解析结果后直连 IP」的方案在本技术栈下不可行——已实测 JDK 21
 *   {@code HttpURLConnection} 会丢弃自定义 {@code Host} 头（受限头部，回显始终为连接 authority），
 *   直连 IP 会同时破坏 https SNI/证书校验与 CDN 虚拟主机路由，得不偿失。
 *   因此以「校验 + 逐跳复检 + 拒绝内嵌凭证」把面压到最小；要彻底闭合需独立出口代理
 *   （固定解析 + 按 Host 转发）或原始 socket 层实现，超出当前范围。</li>
 *   <li>本类拒绝 URL 内嵌凭证（{@code http://user:pass@host/}）：该写法是解析器差异
 *   （parser differential）的常用载体，且会把凭证带进日志与 Referer。</li>
 * </ul>
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
     * @throws IllegalArgumentException URL 非法、协议不允许、携带用户信息或指向内网/保留地址
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

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL 不允许携带用户信息（user:password@）: " + uri.getUserInfo());
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
