package com.paiagent.service;

import com.paiagent.config.MinioConfig;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 迭代7（评审报告 A4 同类修复 · 测试盲区补齐）：URL 转存 MinIO 的 SSRF 防护。
 *
 * <p>转存来源为用户/模型可控 URL，修复后与网页抓取同级：协议白名单 + 内网地址校验，
 * 且校验发生在建立连接之前。测试用不安全 URL 触发拒绝，不做真实网络/MinIO 访问。
 */
class MinioServiceTest {

    private MinioClient minioClient;
    private MinioService minioService;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        minioService = new MinioService();
        ReflectionTestUtils.setField(minioService, "minioClient", minioClient);
        ReflectionTestUtils.setField(minioService, "minioConfig", mock(MinioConfig.class));
    }

    @Test
    @DisplayName("file:// 协议被拒绝，且不触碰 MinIO 客户端")
    void rejectsNonHttpScheme() throws Exception {
        assertRejected("file:///etc/passwd");
    }

    @Test
    @DisplayName("环回地址被拒绝")
    void rejectsLoopback() throws Exception {
        assertRejected("http://127.0.0.1:8080/internal-transfer");
    }

    @Test
    @DisplayName("云元数据地址（链路本地）被拒绝")
    void rejectsMetadataEndpoint() throws Exception {
        assertRejected("http://169.254.169.254/latest/meta-data/");
    }

    @Test
    @DisplayName("内网地址被拒绝")
    void rejectsPrivateNetwork() throws Exception {
        assertRejected("http://192.168.1.20/secret.pdf");
    }

    private void assertRejected(String url) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> minioService.uploadFromUrl(url, "uploads/a.pdf", "application/pdf"));
        verify(minioClient, never()).putObject(ArgumentMatchers.any());
    }
}
