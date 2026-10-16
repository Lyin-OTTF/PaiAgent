package com.paiagent.common;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 启动命令校验（迭代9 P1-6 · 评审报告第九章 🟡6）。
 *
 * <p><b>背景</b>：MCP 工具配置的 {@code command} 此前由请求体直填、随后原样进入
 * {@code new ProcessBuilder(commandLine).start()}——任意已认证调用方都能通过配置接口
 * 指定 {@code cmd}/{@code sh}/任意可执行文件，在服务端执行本地命令（配置写入即 RCE 原语，
 * 且命令随 MCP 会话启动自动执行）。
 *
 * <p><b>策略</b>：默认拒绝 + 启动器白名单。只允许「已知的 MCP 启动器/运行时」，
 * 且命令必须是<b>单个</b>可执行名（或绝对路径），不得包含 shell 元字符，
 * 参数经 {@code ProcessBuilder} 以 argv 形式传递（不经过 shell 解析）。
 *
 * <p>运维如确需自定义启动器，可通过环境变量 {@code PAIAGENT_MCP_ALLOWED_COMMANDS}
 * （逗号分隔）扩展白名单；应把该能力视为「等同于代码执行权限」谨慎开放。
 */
public final class McpCommandValidator {

    /** 受信任的 MCP 启动器/运行时（小写，已去可执行扩展名）。 */
    private static final Set<String> DEFAULT_ALLOWED_EXECUTABLES = Set.of(
            "uvx",
            "uv",
            "npx",
            "pnpx",
            "node",
            "bunx",
            "bun",
            "deno",
            "python",
            "python3",
            "java",
            "mcp-server-askecho-search-infinity"
    );

    /** shell 元字符：出现即说明意图拼接命令而非执行单个可执行文件。 */
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[;&|`$><\r\n\t]");

    private static final Set<String> ALLOWED = resolveAllowedExecutables();

    private McpCommandValidator() {
    }

    /**
     * 校验 MCP 启动命令。
     *
     * @throws IllegalArgumentException 命令为空、包含 shell 元字符/参数、或可执行名不在白名单
     */
    public static void assertSafeCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("MCP 启动命令不能为空");
        }
        String trimmed = command.trim();
        if (SHELL_METACHARACTERS.matcher(trimmed).find()) {
            throw new IllegalArgumentException("MCP 启动命令不得包含 shell 元字符: " + trimmed);
        }
        if (trimmed.contains(" ")) {
            throw new IllegalArgumentException(
                    "MCP 启动命令必须是单个可执行文件（参数请放入 args）: " + trimmed);
        }
        String executable = normalizeExecutableName(trimmed);
        if (!ALLOWED.contains(executable)) {
            throw new IllegalArgumentException(
                    "不允许的 MCP 启动命令 [" + executable + "]，仅允许受信任的启动器: " + ALLOWED);
        }
    }

    /** 是否为白名单内命令（供只读校验/测试使用）。 */
    public static boolean isAllowed(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String trimmed = command.trim();
        if (SHELL_METACHARACTERS.matcher(trimmed).find() || trimmed.contains(" ")) {
            return false;
        }
        return ALLOWED.contains(normalizeExecutableName(trimmed));
    }

    /** 取路径最后一段（兼容 / 与 \\ 分隔），小写并去掉可执行扩展名。 */
    private static String normalizeExecutableName(String command) {
        String name = command;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[] {".exe", ".cmd", ".bat", ".ps1"}) {
            if (lower.endsWith(extension)) {
                lower = lower.substring(0, lower.length() - extension.length());
                break;
            }
        }
        return lower;
    }

    private static Set<String> resolveAllowedExecutables() {
        Set<String> allowed = new LinkedHashSet<>(DEFAULT_ALLOWED_EXECUTABLES);
        String extra = System.getenv("PAIAGENT_MCP_ALLOWED_COMMANDS");
        if (extra != null && !extra.isBlank()) {
            Arrays.stream(extra.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .forEach(allowed::add);
        }
        return Set.copyOf(allowed);
    }
}
