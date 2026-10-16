package com.paiagent.engine.executor.impl;

import com.paiagent.engine.llm.ChatClientFactory;
import com.paiagent.engine.llm.LLMNodeConfig;
import com.paiagent.engine.llm.PromptTemplateService;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.LLMGlobalConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代6：#32 AbstractLLMNodeExecutor 缺陷修复测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code configId}/{@code temperature} 以字符串形式配置时不再强转 {@code Number} 抛 CCE；</li>
 *   <li>{@code buildOutput} 无论是否声明 outputParams 都暴露通用 {@code output}，
 *       且对空白/null 参数名做防御（不再写入 null key）。</li>
 * </ul>
 */
class AbstractLLMNodeExecutorTest {

    /** 用于测试的最小 LLM 执行器实现（getNodeType 抽象方法）。 */
    static class TestLlmExecutor extends AbstractLLMNodeExecutor {
        @Override
        protected String getNodeType() {
            return "llm";
        }
    }

    private TestLlmExecutor executor;
    private ChatClientFactory chatClientFactory;
    private PromptTemplateService promptTemplateService;
    private LLMGlobalConfigService llmGlobalConfigService;

    @BeforeEach
    void setUp() {
        chatClientFactory = mock(ChatClientFactory.class);
        promptTemplateService = mock(PromptTemplateService.class);
        llmGlobalConfigService = mock(LLMGlobalConfigService.class);

        when(promptTemplateService.processTemplate(any(), any(), any())).thenReturn("你好");

        // 注意：必须先构建好 ChatClient 桩，再放进 thenReturn——
        // 若在 thenReturn(...) 参数里创建 mock，会在桩未完成时触发新的 mock 交互，
        // 导致 Mockito 抛 UnfinishedStubbing。
        ChatClient chatClient = stubChatClient("hello");
        when(chatClientFactory.createClient(any(), any(), any(), any(), any()))
                .thenReturn(chatClient);

        executor = new TestLlmExecutor();
        ReflectionTestUtils.setField(executor, "chatClientFactory", chatClientFactory);
        ReflectionTestUtils.setField(executor, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(executor, "llmGlobalConfigService", llmGlobalConfigService);
    }

    private ChatClient stubChatClient(String content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
        return client;
    }

    private Map<String, Object> outputParam(String name) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("name", name);
        param.put("type", "string");
        return param;
    }

    private WorkflowNode llmNode(List<Map<String, Object>> outputParams) {
        WorkflowNode node = new WorkflowNode();
        node.setId("llm-1");
        node.setType("llm");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "openai");
        data.put("apiUrl", "https://api.example.com");
        data.put("apiKey", "k");
        data.put("model", "m");
        data.put("prompt", "你好");
        data.put("temperature", 0.5);
        if (outputParams != null) {
            data.put("outputParams", outputParams);
        }
        node.setData(data);
        return node;
    }

    // ------------------------------------------------------------------
    // buildOutput：通用 output 始终暴露 + 空白参数名防御
    // ------------------------------------------------------------------

    @Test
    @DisplayName("声明 outputParams 时仍暴露通用 output，且各别名映射同一段文本")
    void alwaysExposesGenericOutputEvenWithOutputParams() throws Exception {
        WorkflowNode node = llmNode(List.of(outputParam("summary"), outputParam("keywords")));
        Map<String, Object> output = executor.execute(node, Map.of());

        assertEquals("hello", output.get("output"));
        assertEquals("hello", output.get("summary"));
        assertEquals("hello", output.get("keywords"));
    }

    @Test
    @DisplayName("未声明 outputParams 时输出仅含通用 output")
    void exposesOnlyGenericOutputWhenNoParams() throws Exception {
        WorkflowNode node = llmNode(null);
        Map<String, Object> output = executor.execute(node, Map.of());

        assertEquals("hello", output.get("output"));
        assertEquals(0, output.get("inputTokens"));
        assertEquals(0, output.get("totalTokens"));
    }

    @Test
    @DisplayName("空白/null/重名 output 的参数被跳过，不写入 null key")
    void skipsBlankAndDuplicateOutputNames() throws Exception {
        List<Map<String, Object>> params = new ArrayList<>();
        params.add(outputParam("   "));
        params.add(outputParam(null));
        params.add(outputParam("output"));
        params.add(outputParam("a"));

        Map<String, Object> output = executor.execute(llmNode(params), Map.of());

        assertFalse(output.containsKey(null));
        assertEquals("hello", output.get("a"));
        assertEquals("hello", output.get("output"));
    }

    // ------------------------------------------------------------------
    // extractConfig：字符串型 configId / temperature 不再 CCE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("temperature 以字符串配置时正常解析（修复点）")
    void stringTemperatureDoesNotThrow() {
        WorkflowNode node = llmNode(null);
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.put("temperature", "0.3");
        node.setData(data);

        LLMNodeConfig config = executor.extractConfig(node);

        assertEquals(0.3, config.getTemperature(), 1e-9);
    }

    @Test
    @DisplayName("configId 以字符串配置时正常解析（修复点）")
    void stringConfigIdDoesNotThrow() {
        when(llmGlobalConfigService.getById(any())).thenReturn(null);

        WorkflowNode node = llmNode(null);
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.put("configId", "7");
        node.setData(data);

        LLMNodeConfig config = executor.extractConfig(node);

        assertEquals(7L, config.getConfigId());
    }

    @Test
    @DisplayName("temperature 缺失时回落默认 0.7")
    void missingTemperatureFallsBackToDefault() {
        WorkflowNode node = llmNode(null);
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.remove("temperature");
        node.setData(data);

        assertEquals(0.7, executor.extractConfig(node).getTemperature(), 1e-9);
    }

    @Test
    @DisplayName("temperature 为非法字符串时回落默认 0.7（不抛异常）")
    void invalidTemperatureStringFallsBackToDefault() {
        WorkflowNode node = llmNode(null);
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.put("temperature", "not-a-number");
        node.setData(data);

        assertEquals(0.7, executor.extractConfig(node).getTemperature(), 1e-9);
    }

    @Test
    @DisplayName("configId 为非法字符串时按 null 处理（不抛异常，走节点级配置）")
    void invalidConfigIdIsTreatedAsNull() {
        WorkflowNode node = llmNode(null);
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.put("configId", "abc");
        node.setData(data);

        LLMNodeConfig config = executor.extractConfig(node);

        assertEquals(null, config.getConfigId());
        assertEquals("openai", config.getProvider());
    }
}
