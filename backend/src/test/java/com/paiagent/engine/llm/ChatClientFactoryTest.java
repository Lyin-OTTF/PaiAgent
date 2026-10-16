package com.paiagent.engine.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatClientFactoryTest {

    private final ChatClientFactory factory = new ChatClientFactory();

    @Test
    void createClient_acceptsSkyClawProviderAliasAsOpenAiCompatible() {
        ChatClient client = assertDoesNotThrow(() ->
                factory.createClient(
                        "skyclaw-v1.0",
                        "https://api.apifree.ai/agent/v1/chat/completions",
                        "test-api-key",
                        "skywork-ai/skyclaw-v1",
                        0.7
                )
        );

        assertNotNull(client);
    }

    @Test
    void normalizeModel_mapsSkyClawProductNamesToApifreeModelIds() throws Exception {
        Method normalizeModel = ChatClientFactory.class.getDeclaredMethod("normalizeModel", String.class, String.class);
        normalizeModel.setAccessible(true);

        assertEquals("skywork-ai/skyclaw-v1",
                normalizeModel.invoke(factory, "apifree", "skyclaw-v1.0"));
        assertEquals("skywork-ai/skyclaw-v1-lite",
                normalizeModel.invoke(factory, "apifree", "skyclaw-v1.0-lite"));
    }

    @Test
    void apifreeClient_parsesOpenAiJsonReturnedAsTextPlain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 1779881763,
                      "model": "skywork-ai/skyclaw-v1",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "pong"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ChatClient client = factory.createClient(
                    "apifree",
                    "http://localhost:" + server.getAddress().getPort(),
                    "test-api-key",
                    "skywork-ai/skyclaw-v1",
                    0.7
            );

            String content = client.prompt().user("ping").call().chatResponse()
                    .getResult().getOutput().getContent();

            assertEquals("pong", content);
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 迭代9 P1-5：RestClient 连接/读取超时注入

    /**
     * P1-5：同步路径的 RestClient 必须携带连接与读取超时。
     * 修复前 {@code RestClient.builder()} 用默认工厂（超时为 0=无限），
     * 上游半开连接会把 worker 线程永久占住，有界线程池被逐个耗尽。
     */
    @Test
    void restClientBuilder_appliesConfiguredTimeouts() throws Exception {
        RestClient.Builder builder = ReflectionTestUtils.invokeMethod(factory, "createRestClientBuilder");
        assertNotNull(builder);

        ClientHttpRequestFactory requestFactory =
                (ClientHttpRequestFactory) ReflectionTestUtils.getField(builder, "requestFactory");
        assertInstanceOf(SimpleClientHttpRequestFactory.class, requestFactory);

        Object connectTimeout = ReflectionTestUtils.getField(requestFactory, "connectTimeout");
        Object readTimeout = ReflectionTestUtils.getField(requestFactory, "readTimeout");
        assertNotNull(connectTimeout);
        assertNotNull(readTimeout);
        assertEquals(10_000, ((Number) connectTimeout).intValue(), "连接超时应为 10s");
        assertEquals(120_000, ((Number) readTimeout).intValue(), "读取超时应为 120s");
    }

    /**
     * 字段内联默认值必须与 {@code @Value} 兜底一致：生产环境由 Spring 注入，
     * 但单测/直接 new 时若没有内联默认值，超时会静默变成 0（无限）——本用例锁定默认值。
     */
    @Test
    void timeoutFieldsHaveSaneDefaultsForDirectInstantiation() {
        assertEquals(10_000, ((Number) ReflectionTestUtils.getField(factory, "connectTimeoutMs")).intValue());
        assertEquals(120_000, ((Number) ReflectionTestUtils.getField(factory, "readTimeoutMs")).intValue());
    }
}
