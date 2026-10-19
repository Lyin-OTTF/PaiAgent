package com.paiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedStreamReader} 单元测试（迭代10）：限长读取是各 provider 客户端
 * 替代 {@code readAllBytes()} 的基础设施，超限必须抛错而非静默截断。
 */
class BoundedStreamReaderTest {

    @Test
    @DisplayName("未超限时完整读取")
    void readsFullContentWithinLimit() throws IOException {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(data, BoundedStreamReader.readCapped(new ByteArrayInputStream(data), 1024));
    }

    @Test
    @DisplayName("内容恰好等于上限时可通过")
    void readsExactlyAtLimit() throws IOException {
        byte[] data = new byte[10];

        assertEquals(10, BoundedStreamReader.readCapped(new ByteArrayInputStream(data), 10).length);
    }

    @Test
    @DisplayName("超过上限即抛 IOException，不做静默截断")
    void throwsWhenExceedingLimit() {
        InputStream in = new ByteArrayInputStream(new byte[100]);

        IOException ex = assertThrows(IOException.class, () -> BoundedStreamReader.readCapped(in, 10));
        assertTrue(ex.getMessage().contains("10"), "错误信息应带上上限值");
    }

    @Test
    @DisplayName("null 输入按空数据处理")
    void returnsEmptyForNullInput() throws IOException {
        assertEquals(0, BoundedStreamReader.readCapped(null, 10).length);
    }

    @Test
    @DisplayName("上限非正数直接拒绝（防调用方配置错误被静默忽略）")
    void rejectsNonPositiveLimit() {
        InputStream in = new ByteArrayInputStream(new byte[1]);

        assertThrows(IllegalArgumentException.class, () -> BoundedStreamReader.readCapped(in, 0));
        assertThrows(IllegalArgumentException.class, () -> BoundedStreamReader.readCapped(in, -1));
    }
}
