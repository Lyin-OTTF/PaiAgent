package com.paiagent.engine.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * ChatClient动态工厂
 * 根据节点配置在运行时创建不同类型的ChatClient实例
 *
 * <p><b>迭代9 P1-5（评审报告第九章 🟡5）</b>：原先各分支用
 * {@code new OpenAiApi(url, key)} / {@code RestClient.builder()} 创建客户端，
 * 底层 {@code SimpleClientHttpRequestFactory} 的连接与读取超时都是 0（无限）——
 * 一旦上游半开连接（SYN 黑洞 / 不返回响应），worker 线程会永久阻塞，
 * 有界线程池被逐个耗尽。现统一注入连接/读取超时。
 *
 * <p>说明：{@code WebClient}（流式路径）的超时依赖响应式连接器，本项目未引入
 * reactor-netty（编译期不可用），故流式分支保持默认；同步 {@code call()} 路径
 * 已由 RestClient 超时覆盖——而它正是会阻塞线程池的那条路径。
 */
@Slf4j
@Component
public class ChatClientFactory {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/v1/chat/completions";

    private static final String V1_SUFFIX = "/v1";

    /** LLM 连接超时（毫秒）；字段默认值与 {@code @Value} 兜底一致，便于直接 new 的单测。 */
    @Value("${paiagent.llm.connect-timeout-ms:10000}")
    private int connectTimeoutMs = 10_000;

    /** LLM 读取超时（毫秒）：大模型长回答留足时间。 */
    @Value("${paiagent.llm.read-timeout-ms:120000}")
    private int readTimeoutMs = 120_000;

    /**
     * 根据提供商和配置创建ChatClient
     *
     * @param provider    提供商标识 (openai/deepseek/qwen/step/zhipu/ai_ping/apifree)
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @return ChatClient实例
     */
    /**
     * 根据提供商和配置创建ChatClient。
     *
     * <p>迭代7（死代码清理）：原先另有一个 {@code createClientWithFunctions} 重载，
     * 但没有任何调用方传入非空函数列表（Skill 的能力披露已改为把 references
     * 直接打包进系统提示词），其函数注册分支永久不可达，故整体移除。
     *
     * @param provider    提供商标识 (openai/deepseek/qwen/step/zhipu/ai_ping/apifree)
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @return ChatClient实例
     */
    public ChatClient createClient(String provider, String apiUrl, String apiKey,
                                   String model, Double temperature) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedModel = normalizeModel(normalizedProvider, model);
        if (!normalizedModel.equals(model)) {
            log.warn("检测到 {} 模型别名，已自动归一化: {} -> {}", normalizedProvider, model, normalizedModel);
        }
        log.info("创建ChatClient - 类型: {}, URL: {}, 模型: {}, 温度: {}",
                normalizedProvider, apiUrl, normalizedModel, temperature);

        ChatModel chatModel = switch (normalizedProvider) {
            case "openai", "deepseek", "qwen", "step", "zhipu", "ai_ping", "agnes" ->
                    createOpenAICompatibleModel(apiUrl, apiKey, normalizedModel, temperature);
            case "apifree" ->
                    createApifreeChatModel(apiUrl, apiKey, normalizedModel, temperature);
            case "volcengine_agent_plan" ->
                    createVolcengineArkChatModel(apiUrl, apiKey, normalizedModel, temperature);
            default -> throw new IllegalArgumentException("不支持的提供商类型: " + provider);
        };

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 创建OpenAI兼容的ChatModel
     * 支持OpenAI、DeepSeek和通义千问（通过OpenAI兼容接口）
     */
    private ChatModel createOpenAICompatibleModel(String apiUrl, String apiKey,
                                                   String model, Double temperature) {
        String normalizedApiUrl = normalizeBaseUrl(apiUrl);
        if (!normalizedApiUrl.equals(apiUrl)) {
            log.warn("检测到 OpenAI 兼容接口地址包含路径后缀，已自动归一化: {} -> {}", apiUrl, normalizedApiUrl);
        }

        // 使用构造函数创建OpenAiApi（支持自定义baseUrl）；迭代9：显式注入带超时的 RestClient
        OpenAiApi openAiApi = new OpenAiApi(
                normalizedApiUrl,
                apiKey,
                createRestClientBuilder(),
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        // 创建ChatModel并配置选项
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options);
    }

    /**
     * APIFree 的 SkyClaw agent endpoint 有时会以 text/plain 返回 OpenAI 兼容 JSON。
     * Spring AI 默认 JSON converter 不接收 text/plain，需要为 APIFree 单独放宽响应类型。
     */
    private ChatModel createApifreeChatModel(String apiUrl, String apiKey,
                                             String model, Double temperature) {
        String normalizedApiUrl = normalizeBaseUrl(apiUrl);
        if (!normalizedApiUrl.equals(apiUrl)) {
            log.warn("检测到 APIFree 兼容接口地址包含路径后缀，已自动归一化: {} -> {}", apiUrl, normalizedApiUrl);
        }

        OpenAiApi openAiApi = new OpenAiApi(
                normalizedApiUrl,
                apiKey,
                createTextPlainJsonRestClientBuilder(),
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options);
    }

    /**
     * Agent Plan 的 OpenAI 兼容 Chat 接口路径是 /api/plan/v3/chat/completions。
     * Spring AI 默认会拼 /v1/chat/completions，这里显式覆盖为 Agent Plan 的路径。
     */
    private ChatModel createVolcengineArkChatModel(String apiUrl, String apiKey,
                                                    String model, Double temperature) {
        String baseUrl = normalizeAgentPlanBaseUrl(apiUrl);
        if (!baseUrl.equals(apiUrl)) {
            log.warn("检测到 Agent Plan 配置地址包含接口路径，已自动归一化: {} -> {}{}", apiUrl, baseUrl, "/api/plan/v3/chat/completions");
        }

        OpenAiApi openAiApi = new OpenAiApi(
                baseUrl,
                apiKey,
                "/api/plan/v3/chat/completions",
                "/api/plan/v3/embeddings",
                createRestClientBuilder(),
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options);
    }

    /**
     * Spring AI 的 OpenAiApi 会自行拼接 /v1/chat/completions，
     * 这里统一将用户输入的 URL 归一化为服务根地址，兼容误填完整接口地址的情况。
     */
    private String normalizeBaseUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        normalized = stripSuffixIgnoreCase(normalized, CHAT_COMPLETIONS_SUFFIX);
        normalized = stripSuffixIgnoreCase(normalized, V1_SUFFIX);
        return stripTrailingSlash(normalized);
    }

    private String normalizeAgentPlanBaseUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        normalized = stripSuffixIgnoreCase(normalized, "/api/plan/v3/chat/completions");
        normalized = stripSuffixIgnoreCase(normalized, "/api/plan/v3");
        return stripTrailingSlash(normalized);
    }

    private String stripSuffixIgnoreCase(String value, String suffix) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (!lowerValue.endsWith(suffix)) {
            return value;
        }
        return value.substring(0, value.length() - suffix.length());
    }

    private String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private RestClient.Builder createTextPlainJsonRestClientBuilder() {
        MappingJackson2HttpMessageConverter textPlainJsonConverter = new MappingJackson2HttpMessageConverter();
        textPlainJsonConverter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("application", "*+json"),
                MediaType.TEXT_PLAIN
        ));

        return createRestClientBuilder()
                .messageConverters(converters -> converters.add(0, textPlainJsonConverter));
    }

    /**
     * 创建带超时的 RestClient.Builder（迭代9 P1-5）。
     * 使用 {@link SimpleClientHttpRequestFactory}：底层 HttpURLConnection，
     * 超时语义直观（连接建立 + 单次读取），避免半开连接永久占用线程。
     */
    private RestClient.Builder createRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        return RestClient.builder().requestFactory(requestFactory);
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            return "";
        }

        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "open ai" -> "openai";
            case "deep seek" -> "deepseek";
            case "通义千问" -> "qwen";
            case "stepfun", "阶跃星辰" -> "step";
            case "智谱" -> "zhipu";
            case "ai ping" -> "ai_ping";
            case "agnes ai" -> "agnes";
            case "api free", "apifree.ai", "skyclaw", "skyclaw-v1", "skyclaw-v1.0",
                 "skyclaw-v1-lite", "skyclaw-v1.0-lite", "skywork-ai/skyclaw-v1",
                 "skywork-ai/skyclaw-v1-lite" -> "apifree";
            case "volcengine", "ark", "agent_plan", "agent plan", "火山方舟" -> "volcengine_agent_plan";
            default -> normalized;
        };
    }

    private String normalizeModel(String provider, String model) {
        if (model == null) {
            return "";
        }

        if (!"apifree".equals(provider)) {
            return model;
        }

        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "skyclaw", "skyclaw-v1", "skyclaw-v1.0" -> "skywork-ai/skyclaw-v1";
            case "skyclaw-lite", "skyclaw-v1-lite", "skyclaw-v1.0-lite" -> "skywork-ai/skyclaw-v1-lite";
            default -> model.trim();
        };
    }
}
