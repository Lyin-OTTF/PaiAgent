package com.paiagent.engine.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksMobilePhone_keepingPrefixAndSuffix() {
        String input = "联系方式：13812345678，请回拨";
        String masked = masker.mask(input);
        assertEquals("联系方式：138****5678，请回拨", masked);
        assertTrue(masked.contains("138"));
        assertTrue(masked.contains("5678"));
        assertFalse(masked.contains("13812345678"));
    }

    @Test
    void masksEmail_keepingFirstCharAndDomain() {
        String input = "请发送报告至 alice.wonder@company.com";
        String masked = masker.mask(input);
        assertEquals("请发送报告至 a***@company.com", masked);
    }

    @Test
    void masksIdCard() {
        String input = "身份证 11010519900307891X 已核验";
        String masked = masker.mask(input);
        assertTrue(masked.contains("1101****891X"));
        assertFalse(masked.contains("11010519900307891X"));
    }

    @Test
    void masksBankCard() {
        String input = "卡号 6222021234567890123 入账";
        String masked = masker.mask(input);
        assertTrue(masked.contains("6222****0123"));
        assertFalse(masked.contains("6222021234567890123"));
    }

    @Test
    void detectsSensitive() {
        assertTrue(masker.isSensitive("电话13900001111"));
        assertTrue(masker.isSensitive("mail me at a@b.com"));
        assertFalse(masker.isSensitive("今天天气不错，记忆一条普通事实"));
        assertFalse(masker.isSensitive(null));
        assertFalse(maskedOfEmpty());
    }

    private boolean maskedOfEmpty() {
        return masker.isSensitive("");
    }

    @Test
    void passthroughNonSensitive() {
        String text = "用户偏好使用深色主题";
        assertEquals(text, masker.mask(text));
    }
}
