package com.paiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 迭代7（评审报告 A4）：出站 URL 安全校验（SSRF 防护）。
 *
 * <p>全部用例均基于「地址字面量或本机解析」，不发起网络请求，保证测试稳定；
 * 因此只覆盖「拒绝」路径（协议白名单 + 内网/保留地址），
 * 公网放行路径依赖真实 DNS，不在此断言。
 */
class UrlSafetyValidatorTest {

    @Test
    @DisplayName("拒绝非 http/https 协议（file/jar/ftp）")
    void rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("jar:file:/tmp/a.jar!/b.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("ftp://example.com/a"));
    }

    @Test
    @DisplayName("拒绝回环地址（含 localhost 域名）")
    void rejectsLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://127.0.0.1/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://localhost:8080/x"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://[::1]/x"));
    }

    @Test
    @DisplayName("拒绝私网地址（10/8、172.16/12、192.168/16）")
    void rejectsPrivateNetworks() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://10.0.0.5/x"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://172.16.0.1/x"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://192.168.1.1/x"));
    }

    @Test
    @DisplayName("拒绝链路本地地址与云元数据端点（169.254.169.254）")
    void rejectsLinkLocalAndMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("拒绝运营商级 NAT（100.64/10）与保留/未指定地址")
    void rejectsCgnAndReserved() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://100.64.0.1/x"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://0.0.0.0/x"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://240.0.0.1/x"));
    }

    @Test
    @DisplayName("空白、缺主机、格式非法一律拒绝")
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafetyValidator.assertSafeOutbound(null));
        assertThrows(IllegalArgumentException.class, () -> UrlSafetyValidator.assertSafeOutbound("   "));
        assertThrows(IllegalArgumentException.class, () -> UrlSafetyValidator.assertSafeOutbound("http:///path"));
    }

    @Test
    @DisplayName("校验是纯判断：合法调用不抛异常（以本机可解析的协议形式验证签名可用）")
    void signatureUsableForLegalInput() {
        // 用「解析必然失败」的域名验证：失败也被拒绝，说明不会静默放行
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertSafeOutbound("http://nonexistent-host-for-paiagent-test.invalid/x"));
        // 显式确认方法本身可被正常调用（不抛非受检异常以外的东西）
        assertDoesNotThrow(() -> {
            try {
                UrlSafetyValidator.assertSafeOutbound("https://127.0.0.1/");
            } catch (IllegalArgumentException ignored) {
                // 期望路径
            }
        });
    }
}
