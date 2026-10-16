package com.paiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 迭代9 P0-4 的仓库级回归守卫：<b>仓库不得下发任何可用默认口令</b>。
 *
 * <p>修复前 {@code application.yml} 与 {@code .env.example} 都写着 {@code admin/admin123}，
 * 「克隆即部署」等于公开一个管理员账号。本用例把「默认留空 + 弱口令不落文件」这一属性锁死，
 * 防止后续有人把默认口令再写回去（单测 {@link ConfigValidationRunnerTest} 只能覆盖校验逻辑，
 * 覆盖不到配置文件的字面内容）。
 */
class ShippedConfigSafetyTest {

    private String readClasspathResource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "类路径下找不到 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 只保留真实配置项：注释里可以（也应该）提到 admin123 这类反面示例。 */
    private String stripComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.lines().toList()) {
            if (!line.trim().startsWith("#")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("application.yml 不下发可用默认口令（留空占位）")
    void applicationYmlShipsNoUsableDefaultPassword() throws Exception {
        String yml = readClasspathResource("application.yml");

        assertTrue(yml.contains("default-password: ${APP_AUTH_DEFAULT_PASSWORD:}"),
                "默认口令必须是「环境变量 + 空默认」的占位写法");
        assertFalse(stripComments(yml).contains("admin123"), "application.yml 不得再出现仓库默认弱口令");
    }

    @Test
    @DisplayName(".env.example 不自带可用口令（需使用者自行填写）")
    void envExampleShipsNoUsableDefaultPassword() throws Exception {
        Path example = Path.of(".env.example");
        assumeTrue(Files.exists(example), "未在项目根目录运行，跳过 .env.example 检查");

        String text = Files.readString(example, StandardCharsets.UTF_8);
        String effective = stripComments(text);

        assertFalse(effective.contains("admin123"), ".env.example 不得再自带仓库默认弱口令");
        assertTrue(effective.contains("APP_AUTH_DEFAULT_PASSWORD="), "应保留默认账户配置项供使用者填写");
        assertFalse(effective.contains("APP_AUTH_DEFAULT_PASSWORD=admin"),
                "示例值不得是一个直接可用的弱口令");
    }
}
