package com.paiagent.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SafeHttpFetcher 单元测试（迭代9 P1-7：TTS 音频下载 / MinIO 转存统一收敛到本实现）。
 *
 * <p>覆盖两部分：
 * <ol>
 *   <li>出站目标安全校验：非 http/https、回环、链路本地（云元数据）、内嵌凭证一律拒绝；</li>
 *   <li>限长读取语义：<b>文本</b>静默截断（{@code fetchText}），<b>二进制</b>超限直接失败
 *       （{@code fetchBytes}）——静默截断会产出损坏音频/文件，必须显式失败。</li>
 * </ol>
 *
 * <p>说明：安全校验会拒绝回环地址，故无法用本地 HTTP 服务做端到端正例；
 * 限长逻辑通过私有静态助手直接验证（这是「长度上限」这一安全属性的真正落点）。
 */
class SafeHttpFetcherTest {

    // ---------------------------------------------------------------- 出站安全校验

    @Test
    void fetchBytesShouldRejectUnsafeTargets() {
        // 云元数据（链路本地 169.254.0.0/16）
        assertThrows(IllegalArgumentException.class,
                () -> SafeHttpFetcher.fetchBytes("http://169.254.169.254/latest/meta-data/", 1000, 1000, 1024));
        // 回环
        assertThrows(IllegalArgumentException.class,
                () -> SafeHttpFetcher.fetchBytes("http://127.0.0.1:9000/audio.mp3", 1000, 1000, 1024));
        // 非 http/https 协议
        assertThrows(IllegalArgumentException.class,
                () -> SafeHttpFetcher.fetchBytes("file:///etc/passwd", 1000, 1000, 1024));
    }

    @Test
    void fetchTextShouldRejectEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeHttpFetcher.fetchText("http://user:pass@example.com/", 1000, 1000, 1024));
    }

    // ---------------------------------------------------------------- 限长读取

    /** 二进制：超过上限必须抛 IOException，绝不静默截断（截断=损坏文件）。 */
    @Test
    void readLimitedBytesShouldFailOnOversizeBody() {
        byte[] payload = new byte[64];
        Arrays.fill(payload, (byte) 'x');

        IOException ex = assertThrows(IOException.class,
                () -> invokeReadLimitedBytes(new ByteArrayInputStream(payload), 16));
        assertTrue(ex.getMessage().contains("超过上限"), ex.getMessage());
    }

    /** 二进制：恰好等于上限应放行（边界内允许）。 */
    @Test
    void readLimitedBytesShouldAllowBodyExactlyAtLimit() throws Exception {
        byte[] payload = new byte[16];
        Arrays.fill(payload, (byte) 'a');
        assertArrayEquals(payload, invokeReadLimitedBytes(new ByteArrayInputStream(payload), 16));
    }

    /** 文本：超过上限静默截断到上限（正文抓取场景的可接受语义）。 */
    @Test
    void readLimitedShouldTruncateTextSilently() throws Exception {
        byte[] payload = new byte[64];
        Arrays.fill(payload, (byte) 'y');

        String result = invokeReadLimited(new ByteArrayInputStream(payload), 8);
        assertEquals(8, result.length(), "文本读取应截断到上限而非失败");
    }

    // ---------------------------------------------------------------- 私有助手反射入口

    private static byte[] invokeReadLimitedBytes(InputStream in, int maxBytes) throws Exception {
        return (byte[]) invokePrivate("readLimitedBytes", new Class<?>[]{InputStream.class, int.class, String.class},
                in, maxBytes, "http://example.com/a.mp3");
    }

    private static String invokeReadLimited(InputStream in, int maxBytes) throws Exception {
        return (String) invokePrivate("readLimited", new Class<?>[]{InputStream.class, int.class},
                in, maxBytes);
    }

    private static Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SafeHttpFetcher.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
