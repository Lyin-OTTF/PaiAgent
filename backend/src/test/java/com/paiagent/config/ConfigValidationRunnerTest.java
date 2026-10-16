package com.paiagent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代9 P0-4：默认管理员账户弱口令 fail-fast。
 *
 * <p>修复前 {@code application.yml} 默认下发 {@code admin/admin123}（弱口令黑名单还不含
 * admin123），部署即等同于公开一个管理员账号，且启动只打印「✅ 已配置」。
 * 现改为：默认留空=禁用；一旦显式配置弱口令即拒绝启动。
 */
class ConfigValidationRunnerTest {

    private ConfigValidationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ConfigValidationRunner();
    }

    private void configure(String username, String password) {
        ReflectionTestUtils.setField(runner, "defaultUsername", username);
        ReflectionTestUtils.setField(runner, "defaultPassword", password);
    }

    private void invokeValidation() {
        ReflectionTestUtils.invokeMethod(runner, "validateDefaultCredentials");
    }

    @Test
    @DisplayName("留空 → 默认账户禁用，启动正常")
    void disabledWhenBlank() {
        configure("", "");
        assertDoesNotThrow(this::invokeValidation);
    }

    @Test
    @DisplayName("仓库默认口令 admin/admin123 → 拒绝启动")
    void failsFastOnRepositoryDefaultPassword() {
        configure("admin", "admin123");
        IllegalStateException ex = assertThrows(IllegalStateException.class, this::invokeValidation);
        assertTrue(ex.getMessage().contains("Weak"));
    }

    @Test
    @DisplayName("其它常见弱口令（admin/123456/root/password）→ 拒绝启动")
    void failsFastOnOtherWeakPasswords() {
        for (String weak : new String[] {"admin", "123456", "root", "password", "PASSWORD123"}) {
            configure("admin", weak);
            assertThrows(IllegalStateException.class, this::invokeValidation,
                    "弱口令应拒绝启动: " + weak);
        }
    }

    @Test
    @DisplayName("口令过短（< 8 字符）→ 拒绝启动")
    void failsFastOnShortPassword() {
        configure("admin", "Ab1!x");
        assertThrows(IllegalStateException.class, this::invokeValidation);
    }

    @Test
    @DisplayName("强口令 → 启动正常")
    void acceptsStrongPassword() {
        configure("ops-user", "Str0ng-Passw0rd-2026");
        assertDoesNotThrow(this::invokeValidation);
    }

    @Test
    @DisplayName("仅配置用户名未配置口令 → 视为未启用（不打码、不启动账户）")
    void treatsPartialConfigurationAsDisabled() {
        configure("admin", "");
        assertDoesNotThrow(this::invokeValidation);
    }
}
