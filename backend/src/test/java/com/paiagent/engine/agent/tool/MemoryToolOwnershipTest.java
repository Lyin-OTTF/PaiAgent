package com.paiagent.engine.agent.tool;

import com.paiagent.common.UserContext;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.engine.memory.MemoryWriteRequest;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代9 P0-1：分层记忆工具的用户/租户/会话隔离。
 *
 * <p>评审报告第九章 🔴1：修复前 {@code userId}/{@code sessionId}/{@code tenantId} 全部取自
 * 模型入参（输入 schema 明确暴露），提示注入即可诱导 LLM 以 {@code userId=受害者}
 * 读他人 USER 层记忆、向任意租户写污染记忆。修复后归属维度由服务端投影。
 */
class MemoryToolOwnershipTest {

    private AgentMemoryService memoryService;
    private AgentPlanConfigResolver configResolver;
    private VolcengineAgentPlanClient agentPlanClient;
    private WorkflowNode node;

    @BeforeEach
    void setUp() {
        memoryService = mock(AgentMemoryService.class);
        configResolver = mock(AgentPlanConfigResolver.class);
        agentPlanClient = mock(VolcengineAgentPlanClient.class);
        // 未配置向量模型：跳过 embedding 调用，聚焦归属投影
        when(configResolver.resolve(any(), any())).thenReturn(
                new ResolvedAgentPlanConfig(null, null, null, null, null, null, null, null, false));
        node = new WorkflowNode();
        node.setId("react-1");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("检索：模型伪造的 userId/tenantId/sessionId 被忽略，改用服务端投影")
    void retrieveIgnoresModelSuppliedIdentity() throws Exception {
        UserContext.setCurrent("alice");
        TenantContext.setCurrent("tenantA");

        MemoryRetrieveTool tool = new MemoryRetrieveTool(configResolver, agentPlanClient, memoryService);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", "偏好");
        arguments.put("userId", "victim");
        arguments.put("tenantId", "other-tenant");
        arguments.put("sessionId", "attacker-session");

        tool.execute(arguments, new AgentToolContext(node, Map.of("executionId", 100)));

        ArgumentCaptor<MemoryQueryRequest> captor = ArgumentCaptor.forClass(MemoryQueryRequest.class);
        verify(memoryService).retrieve(captor.capture());
        MemoryQueryRequest request = captor.getValue();
        assertEquals("alice", request.getUserId(), "userId 必须来自登录态");
        assertEquals("tenantA", request.getTenantId(), "tenantId 必须来自租户投影");
        assertEquals("exec:100", request.getSessionId(), "sessionId 必须由执行身份派生");
    }

    @Test
    @DisplayName("写入：模型伪造的归属被忽略，写入绑定到当前用户与租户")
    void writeIgnoresModelSuppliedIdentity() throws Exception {
        UserContext.setCurrent("alice");
        TenantContext.setCurrent("tenantA");

        MemoryWriteTool tool = new MemoryWriteTool(configResolver, agentPlanClient, memoryService);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("content", "用户偏好深色主题");
        arguments.put("userId", "victim");
        arguments.put("tenantId", "other-tenant");
        arguments.put("sessionId", "attacker-session");

        tool.execute(arguments, new AgentToolContext(node, Map.of("executionId", 100)));

        ArgumentCaptor<MemoryWriteRequest> captor = ArgumentCaptor.forClass(MemoryWriteRequest.class);
        verify(memoryService).write(captor.capture());
        MemoryWriteRequest request = captor.getValue();
        assertEquals("alice", request.getUserId());
        assertEquals("tenantA", request.getTenantId());
        assertEquals("exec:100", request.getSessionId());
    }

    @Test
    @DisplayName("无请求上下文时归属退化为 system（而非空），写操作不会产生无归属数据")
    void fallsBackToSystemOwnerWithoutContext() throws Exception {
        MemoryWriteTool tool = new MemoryWriteTool(configResolver, agentPlanClient, memoryService);

        tool.execute(Map.of("content", "x"), new AgentToolContext(node, Map.of()));

        ArgumentCaptor<MemoryWriteRequest> captor = ArgumentCaptor.forClass(MemoryWriteRequest.class);
        verify(memoryService).write(captor.capture());
        assertEquals(UserContext.SYSTEM_OWNER, captor.getValue().getUserId());
        assertEquals("global", captor.getValue().getTenantId());
    }

    @Test
    @DisplayName("输入 schema 不再暴露 userId/sessionId/tenantId")
    void schemaDoesNotExposeIdentityFields() {
        MemoryRetrieveTool retrieve = new MemoryRetrieveTool(configResolver, agentPlanClient, memoryService);
        MemoryWriteTool write = new MemoryWriteTool(configResolver, agentPlanClient, memoryService);

        for (String field : new String[] {"userId", "sessionId", "tenantId"}) {
            assertFalse(retrieve.getInputSchema().contains(field), "检索 schema 不应暴露 " + field);
            assertFalse(write.getInputSchema().contains(field), "写入 schema 不应暴露 " + field);
        }
    }
}
