package com.paiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代7（评审报告 A3）：API 密钥脱敏与掩码识别。
 * 掩码识别是「保存时不把掩码写回数据库」的保护前提。
 */
class ApiKeyMaskerTest {

    @Test
    @DisplayName("长密钥保留前 3 位与后 4 位，中段打码")
    void masksLongKey() {
        assertEquals("sk-****cdef", ApiKeyMasker.mask("sk-1234567890abcdef"));
    }

    @Test
    @DisplayName("过短密钥整体打码（保留前后缀反而暴露大部分内容）")
    void masksShortKeyEntirely() {
        assertEquals("****", ApiKeyMasker.mask("abcdefg"));
        assertEquals("****", ApiKeyMasker.mask("ab"));
    }

    @Test
    @DisplayName("null/空白原样返回，不产生额外字符")
    void handlesBlank() {
        assertNull(ApiKeyMasker.mask(null));
        assertEquals("", ApiKeyMasker.mask(""));
    }

    @Test
    @DisplayName("isMasked 精确识别打码形态")
    void isMaskedRecognisesMaskedForm() {
        assertTrue(ApiKeyMasker.isMasked(ApiKeyMasker.mask("sk-1234567890abcdef")));
        assertTrue(ApiKeyMasker.isMasked("****"));
    }

    @Test
    @DisplayName("真实密钥不被误判为掩码（否则保存时会被错误替换成库中原值）")
    void isMaskedDoesNotMisjudgeRealKey() {
        assertFalse(ApiKeyMasker.isMasked("sk-1234567890abcdef"));
        assertFalse(ApiKeyMasker.isMasked(null));
        // 含 **** 但长度不符合掩码形态
        assertFalse(ApiKeyMasker.isMasked("sk-****x"));
    }
}
