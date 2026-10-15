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
        result = BANK_CARD.matcher(result).replaceAll(m -> maskKeep(m.group(), 4, 4));
        return result;
    }

    /** 判断内容是否包含敏感信息 */
    public boolean isSensitive(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return PHONE.matcher(content).find()
                || ID_CARD.matcher(content).find()
                || BANK_CARD.matcher(content).find()
                || EMAIL.matcher(content).find();
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String maskKeep(String value, int head, int tail) {
        if (value.length() <= head + tail) {
            return "****";
        }
        return value.substring(0, head) + "****" + value.substring(value.length() - tail);
    }
}
