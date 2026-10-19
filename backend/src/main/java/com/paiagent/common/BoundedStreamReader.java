package com.paiagent.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 限长读取工具（迭代10 · SSRF/DoS 收口配套）。
 *
 * <p>修复前图片/视频/AgentPlan 客户端用 {@code InputStream.readAllBytes()} 读上游响应：
 * 恶意或异常端点返回超大响应体即可把堆内存打爆（{@code OutOfMemoryError}），
 * 与连接/读取超时一样属于「上游不可信」的基本面。本工具把读取量收敛到显式上限，
 * 超限立即抛错，不做静默截断（截断会把半截 JSON/二进制当成完整响应，错误更难排查）。
 *
 * <p>与 {@link SafeHttpFetcher} 内部的限长读取同一语义；单独抽出以便各 provider
 * 客户端复用（它们不走 SafeHttpFetcher，直接持有 HttpURLConnection）。
 */
public final class BoundedStreamReader {

    /** 单次读取缓冲。 */
    private static final int CHUNK = 8192;

    private BoundedStreamReader() {
    }

    /**
     * 读取输入流全部内容，但不超过 {@code maxBytes}。
     *
     * @param in       输入流（可为 null，按空数据处理）
     * @param maxBytes 允许的最大字节数，必须为正数
     * @return 读取到的字节（未超限时即流的全部内容）
     * @throws IOException              读取失败，或内容超过 {@code maxBytes}
     * @throws IllegalArgumentException {@code maxBytes} 非正数
     */
    public static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("读取上限必须为正数: " + maxBytes);
        }
        if (in == null) {
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, CHUNK));
        byte[] buffer = new byte[CHUNK];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) {
                throw new IOException("响应体超过上限 " + maxBytes + " 字节，已中止读取");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
