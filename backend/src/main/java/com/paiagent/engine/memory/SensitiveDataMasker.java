package com.paiagent.engine.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器（迭代2）。
 *
 * 在记忆写入落库前对内容做脱敏，避免将手机号/邮箱/身份证/银行卡等 PII 明文持久化。
 * 采用「保留首尾、中间打码」策略，兼顾可辨识性与安全性。
 */
@Component
public class SensitiveDataMasker {

    /** 手机号：1 开头，第二位 3-9，共 11 位 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    /** 身份证：17 位数字 + 校验位（数字或 X） */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)");
    /** 银行卡：15-19 位连续数字 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{15,19})(?!\\d)");
    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 对内容进行脱敏。多个规则按顺序应用（先邮箱/手机，再身份证，最后银行卡）。
     *
     * @param content 原始内容，可能为 null
     * @return 脱敏后的内容；null/空原样返回
     */
    public String mask(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String result = EMAIL.matcher(content).replaceAll(m -> maskEmail(m.group()));
        result = PHONE.matcher(result).replaceAll(m -> maskKeep(m.group(), 3, 4));
        result = ID_CARD.matcher(result).replaceAll(m -> maskKeep(m.group(), 4, 4));
        // 迭代7：银行卡候选串还需通过 Luhn 校验才打码。
        // 修复前 15-19 位连续数字一律打码，订单号/流水号/时间戳等普通长数字也被误伤，
        // 污染记忆内容、损害检索质量。
        result = BANK_CARD.matcher(result).replaceAll(m ->
                luhnValid(m.group()) ? maskKeep(m.group(), 4, 4) : m.group());
        return result;
    }

    /** 判断内容是否包含敏感信息 */
    public boolean isSensitive(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        if (PHONE.matcher(content).find()
                || ID_CARD.matcher(content).find()
                || EMAIL.matcher(content).find()) {
            return true;
        }
        // 银行卡同样用 Luhn 过滤，避免把普通长数字误判为敏感
        var bankMatcher = BANK_CARD.matcher(content);
        while (bankMatcher.find()) {
            if (luhnValid(bankMatcher.group())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Luhn（模 10）校验：银行卡号满足该校验，订单号/流水号等通常不满足。
     * 用于把「15-19 位数字」这个粗粒度模式收敛到真正的卡号。
     */
    private boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        // 迭代7：单字符本地部分（a@x.com）此前被原样返回而泄露，现统一打码
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String maskKeep(String value, int head, int tail) {
        if (value.length() <= head + tail) {
            return "****";
        }
        return value.substring(0, head) + "****" + value.substring(value.length() - tail);
    }
}
