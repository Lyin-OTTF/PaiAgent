package com.paiagent.engine.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 迭代7（评审报告 A4 边界闭合）：LLM 可直呼的 {@code web_fetch} 工具 SSRF 防护。
 *
 * <p>修复前本工具与 {@code WebFetchNodeExecutor} 各写一份抓取逻辑，加固只补了后者；
 * 现统一走 {@code SafeHttpFetcher}。测试用不安全地址触发<b>连接前</b>的校验拒绝，
 * 不做任何真实网络访问。
 */
class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();

    @Test
    @DisplayName("缺少 url 参数时报参数错误")
    void rejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute(Map.of(), null));
    }

    @Test
    @DisplayName("file:// 等非 http(s) 协议被拒绝")
    void rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("url", "file:///etc/passwd"), null));
    }

    @Test
    @DisplayName("环回地址被拒绝")
    void rejectsLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("url", "http://127.0.0.1/admin"), null));
    }

    @Test
    @DisplayName("云元数据地址（链路本地 169.254/16）被拒绝")
    void rejectsMetadataEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("url", "http://169.254.169.254/latest/meta-data/"), null));
    }

    @Test
    @DisplayName("内网地址（10/8）被拒绝")
    void rejectsPrivateNetwork() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("url", "http://10.182.0.7/internal"), null));
    }

    @Test
    @DisplayName("urls 数组中任一不安全地址都会使整次调用被拒绝")
    void rejectsUnsafeEntryInsideList() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("urls", List.of("http://127.0.0.1/a", "http://169.254.169.254/b")), null));
    }
}
