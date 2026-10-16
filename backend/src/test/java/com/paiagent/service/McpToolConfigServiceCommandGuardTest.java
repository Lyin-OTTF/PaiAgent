package com.paiagent.service;

import com.paiagent.dto.McpToolConfigRequest;
import com.paiagent.entity.McpToolConfig;
import com.paiagent.mapper.McpToolConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代9 P1-6 的接线测试：MCP 配置写入接口必须在落库前校验启动命令白名单。
 *
 * <p>{@code McpCommandValidator} 自身已有单测，但「配置接口确实调用了它」是这次修复的关键——
 * 修复前 {@code request.getCommand()} 直填 {@code McpToolConfig}，随后被
 * {@code ProcessBuilder} 原样执行，任意已认证调用方即可在服务端执行本地命令。
 * 本用例锁死「危险命令在落库前即被拒」，防止后续重构把校验点挪出写入路径。
 */
class McpToolConfigServiceCommandGuardTest {

    private McpToolConfigMapper mapper;
    private McpToolConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(McpToolConfigMapper.class);
        when(mapper.insert(any(McpToolConfig.class))).thenReturn(1);
        service = new McpToolConfigService(mock(SearchInfinityMcpClient.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private McpToolConfigRequest request(String command) {
        McpToolConfigRequest request = new McpToolConfigRequest();
        request.setName("demo");
        request.setToolName("demo_tool");
        request.setTransport("stdio");
        request.setCommand(command);
        return request;
    }

    @Test
    @DisplayName("非白名单启动器（bash/sh/curl 等）→ 落库前拒绝")
    void rejectsNonWhitelistedLauncherBeforePersisting() {
        for (String command : new String[] {"bash", "/bin/sh", "curl", "powershell"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createConfig(request(command)), "应拒绝: " + command);
            assertTrue(ex.getMessage().contains("不允许的 MCP 启动命令"), ex.getMessage());
        }
        verify(mapper, never()).insert(any(McpToolConfig.class));
    }

    @Test
    @DisplayName("命令注入元字符（; | & $ 换行等）→ 落库前拒绝")
    void rejectsShellMetacharactersBeforePersisting() {
        for (String command : new String[] {"npx; rm -rf /", "npx && curl evil", "npx | sh", "npx\nwhoami"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createConfig(request(command)), "应拒绝: " + command);
        }
        verify(mapper, never()).insert(any(McpToolConfig.class));
    }

    @Test
    @DisplayName("白名单启动器 → 正常落库")
    void acceptsWhitelistedLauncher() {
        service.createConfig(request("npx"));

        verify(mapper).insert(any(McpToolConfig.class));
    }
}
