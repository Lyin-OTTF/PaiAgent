package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.entity.McpToolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代8（评审报告 P2 #11）：{@link SearchInfinityMcpClient} 单测。
 *
 * <p>该类此前零测试（需真实 uvx 子进程）。改造为可注入 {@link SearchInfinityMcpClient.SessionFactory}
 * 后，用假会话验证：会话建立的有界重试（迭代7 #14）、握手只做一次（迭代8 修复重复 initialize）、
 * 工具参数构造（Count 钳制 / SearchType 默认）与结果归一化（WebResults / 引用 / 业务错误识别）。
 */
class SearchInfinityMcpClientTest {

    /** 假会话句柄：记录 initialize 次数、工具入参，可编程握手失败。 */
    static final class FakeSession implements SearchInfinityMcpClient.McpSessionHandle {
        int initializeCalls;
        int failInitializeTimes;
        boolean failInitializeWithTimeout;
        JSONObject toolResult = new JSONObject();
        boolean closed;
        final List<JSONObject> toolCalls = new ArrayList<>();

        @Override
        public void initialize() throws IOException, TimeoutException {
            initializeCalls++;
            if (failInitializeTimes > 0) {
                failInitializeTimes--;
                throw new IOException("MCP 握手失败");
            }
            if (failInitializeWithTimeout) {
                throw new TimeoutException("initialize timed out");
            }
        }

        @Override
        public JSONObject callTool(String name, JSONObject arguments) {
            toolCalls.add(arguments);
            return toolResult;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<FakeSession> sessions = new ArrayList<>();
    private int startFailures;
    private final List<Object[]> factoryCalls = new ArrayList<>();

    private final SearchInfinityMcpClient.SessionFactory factory = (command, args, env) -> {
        factoryCalls.add(new Object[]{command, args, env});
        if (startFailures > 0) {
            startFailures--;
            throw new IOException("uvx 拉包失败");
        }
        FakeSession session = new FakeSession();
        sessions.add(session);
        return session;
    };

    private SearchInfinityMcpClient client() {
        return new SearchInfinityMcpClient(factory);
    }

    private static McpToolConfig mcpConfig(String command) {
        McpToolConfig config = new McpToolConfig();
        config.setCommand(command);
        config.setArgs("[\"--port\",\"8080\"]");
        config.setEnv("{\"SEARCH_API_KEY\":\"k\"}");
        return config;
    }

    private static JSONObject textResult(String... texts) {
        JSONArray content = new JSONArray();
        for (String text : texts) {
            content.add(JSONObject.of("text", text));
        }
        return JSONObject.of("content", content);
    }

    @Test
    @DisplayName("会话建立：start 瞬时失败重试一次后成功，失败会话被关闭")
    void retriesSessionStartOnce() throws Exception {
        startFailures = 1;
        SearchInfinityMcpClient client = client();
        sessions.clear();

        Map<String, Object> output = client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null);

        assertFalse(output.isEmpty());
        assertEquals(2, factoryCalls.size(), "失败一次后应重试，共 2 次 start");
        assertEquals(1, sessions.size(), "第二次才成功建立会话");
    }

    @Test
    @DisplayName("会话建立：握手失败同样重试，且失败的会话被 close")
    void retriesHandshakeFailureAndClosesFailedSession() throws Exception {
        SearchInfinityMcpClient client = client();
        FakeSession failing = new FakeSession();
        failing.failInitializeTimes = 1;
        SearchInfinityMcpClient.SessionFactory flaky = (command, args, env) -> {
            factoryCalls.add(new Object[]{command, args, env});
            if (sessions.isEmpty()) {
                sessions.add(failing);
                return failing;
            }
            FakeSession good = new FakeSession();
            sessions.add(good);
            return good;
        };
        client = new SearchInfinityMcpClient(flaky);

        client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null);

        assertEquals(2, factoryCalls.size());
        assertEquals(1, failing.initializeCalls, "失败会话只握手一次");
        assertTrue(failing.closed, "握手失败的会话必须被关闭");
        assertEquals(1, sessions.get(1).initializeCalls);
    }

    @Test
    @DisplayName("会话建立：两次都失败 → 抛最后一次的 IOException")
    void throwsWhenAllAttemptsFail() {
        startFailures = 2;
        SearchInfinityMcpClient client = client();

        IOException error = assertThrows(IOException.class,
                () -> client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null));

        assertTrue(error.getMessage().contains("uvx 拉包失败"), error.getMessage());
        assertEquals(2, factoryCalls.size());
    }

    @Test
    @DisplayName("会话建立：首次成功不重试；每次会话只 initialize 一次（迭代8 修复重复握手）")
    void initializesExactlyOncePerSession() throws Exception {
        SearchInfinityMcpClient client = client();

        client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null);

        assertEquals(1, factoryCalls.size(), "首次成功不应重试");
        assertEquals(1, sessions.get(0).initializeCalls, "openSession 已握手，doWebSearch 不应重复 initialize");
    }

    @Test
    @DisplayName("握手超时：重试后仍超时 → doWebSearch 包装为带提示的 IOException")
    void wrapsHandshakeTimeout() {
        SearchInfinityMcpClient.SessionFactory timingOut = (command, args, env) -> {
            factoryCalls.add(new Object[]{command, args, env});
            FakeSession session = new FakeSession();
            session.failInitializeWithTimeout = true;
            sessions.add(session);
            return session;
        };
        SearchInfinityMcpClient client = new SearchInfinityMcpClient(timingOut);

        IOException error = assertThrows(IOException.class,
                () -> client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null));

        assertTrue(error.getMessage().contains("超时"), error.getMessage());
        assertEquals(2, factoryCalls.size(), "超时也应有界重试一次");
        assertTrue(sessions.get(0).closed, "超时的会话应被关闭");
    }

    @Test
    @DisplayName("参数构造：command/args/env 解析后传给工厂；非 uvx 命令原样透传")
    void passesParsedCommandArgsEnvToFactory() throws Exception {
        SearchInfinityMcpClient client = client();

        client.webSearch(mcpConfig("custom-mcp"), "天气", 5, null, null, null);

        Object[] call = factoryCalls.get(0);
        assertEquals("custom-mcp", call[0]);
        assertEquals(List.of("--port", "8080"), call[1]);
        assertEquals(Map.of("SEARCH_API_KEY", "k"), call[2]);
    }

    @Test
    @DisplayName("参数构造：Count 按 searchType 钳制（web≤50 / image≤5），SearchType 默认 web")
    void clampsCountAndDefaultsSearchType() throws Exception {
        SearchInfinityMcpClient client = client();

        client.webSearch(mcpConfig("custom-mcp"), "新闻", 100, "week", 2, null);
        client.webSearch(mcpConfig("custom-mcp"), "图片", 100, null, null, "image");

        JSONObject web = sessions.get(0).toolCalls.get(0);
        assertEquals(50, web.getIntValue("Count"));
        assertEquals("web", web.getString("SearchType"));
        assertEquals("week", web.getString("TimeRange"));
        assertEquals(2, web.getIntValue("AuthLevel"));
        assertEquals("新闻", web.getString("Query"), "原始 query 应透传");

        JSONObject image = sessions.get(1).toolCalls.get(0);
        assertEquals(5, image.getIntValue("Count"));
        assertEquals("image", image.getString("SearchType"));
    }

    @Test
    @DisplayName("结果归一化：WebResults 提取、引用生成、RequestId/ResultCount 透出")
    void normalizesWebResultsAndCitations() throws Exception {
        SearchInfinityMcpClient client = client();
        sessions.clear();
        FakeSession session = new FakeSession();
        sessions.add(session);
        SearchInfinityMcpClient.SessionFactory fixed = (c, a, e) -> session;
        client = new SearchInfinityMcpClient(fixed);
        session.toolResult = textResult("{\"WebResults\":["
                + "{\"Id\":\"1\",\"Title\":\"标题A\",\"Url\":\"https://a.com/x\",\"SiteName\":\"站点A\",\"Snippet\":\"摘要A\"},"
                + "{\"Id\":\"2\",\"Title\":\"标题B\",\"Url\":\"https://b.com/y\",\"SiteName\":\"站点B\",\"Snippet\":\"摘要B\"}"
                + "],\"ResultCount\":2,\"RequestId\":\"req-1\"}");

        Map<String, Object> output = client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> webResults = (List<Map<String, Object>>) output.get("webResults");
        assertEquals(2, webResults.size());
        assertEquals("标题A", webResults.get(0).get("title"));
        assertEquals("https://b.com/y", webResults.get(1).get("url"));
        assertEquals("站点A", webResults.get(0).get("siteName"));
        assertEquals("req-1", output.get("requestId"));
        assertEquals(2, output.get("resultCount"));
        assertEquals(Boolean.FALSE, output.get("isError"));
        assertTrue(String.valueOf(output.get("summary")).contains("1. 标题A"), String.valueOf(output.get("summary")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) output.get("citations");
        assertEquals(2, citations.size());
        assertEquals("https://a.com/x", citations.get(0).get("url"));
        assertEquals("标题A", citations.get(0).get("title"));
    }

    @Test
    @DisplayName("结果归一化：无 ResultCount 字段时回落 WebResults 数量")
    void fallsBackResultCountToWebResultsSize() throws Exception {
        FakeSession session = new FakeSession();
        session.toolResult = textResult("{\"WebResults\":[{\"Id\":\"1\",\"Url\":\"https://a.com\"}]}");
        SearchInfinityMcpClient client = new SearchInfinityMcpClient((c, a, e) -> session);

        Map<String, Object> output = client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null);

        assertEquals(1, output.get("resultCount"));
    }

    @Test
    @DisplayName("结果归一化：isError=true → IOException 且带摘要")
    void throwsOnToolErrorFlag() {
        FakeSession session = new FakeSession();
        session.toolResult = JSONObject.of("isError", true)
                .fluentPut("content", JSONArray.of(JSONObject.of("text", "上游搜索服务不可用")));
        SearchInfinityMcpClient client = new SearchInfinityMcpClient((c, a, e) -> session);

        IOException error = assertThrows(IOException.class,
                () -> client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null));

        assertTrue(error.getMessage().contains("上游搜索服务不可用"), error.getMessage());
    }

    @Test
    @DisplayName("结果归一化：识别嵌套 ResponseMetadata.Error 业务错误")
    void throwsOnBusinessError() {
        FakeSession session = new FakeSession();
        session.toolResult = textResult("{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AuthFailure\",\"Message\":\"密钥无效\"}}}");
        SearchInfinityMcpClient client = new SearchInfinityMcpClient((c, a, e) -> session);

        IOException error = assertThrows(IOException.class,
                () -> client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null));

        assertTrue(error.getMessage().contains("AuthFailure: 密钥无效"), error.getMessage());
    }

    @Test
    @DisplayName("结果归一化：structuredContent 存在时优先采用，content 文本仍计入摘要")
    void prefersStructuredContent() throws Exception {
        FakeSession session = new FakeSession();
        session.toolResult = JSONObject.of(
                "structuredContent", JSONObject.of("result",
                        JSONObject.of("WebResults", JSONArray.of(JSONObject.of("Id", "1", "Url", "https://a.com")))),
                "content", JSONArray.of(JSONObject.of("text", "这是文本摘要")));
        SearchInfinityMcpClient client = new SearchInfinityMcpClient((c, a, e) -> session);

        Map<String, Object> output = client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null);

        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) output.get("results");
        assertEquals(1, results.size(), "structuredContent 存在时不再解析 content 文本为结果项");
        assertInstanceOf(JSONObject.class, results.get(0));
        assertEquals("这是文本摘要", output.get("rawSummary"));
        assertEquals(1, ((List<?>) output.get("webResults")).size());
    }

    @Test
    @DisplayName("结果归一化：纯文本 content 逐条拼接为 rawSummary")
    void joinsPlainTextContent() throws Exception {
        FakeSession session = new FakeSession();
        session.toolResult = textResult("第一段", "第二段");
        SearchInfinityMcpClient client = new SearchInfinityMcpClient((c, a, e) -> session);

        Map<String, Object> output = client.webSearch(mcpConfig("custom-mcp"), "查询", 10, null, null, null);

        assertEquals("第一段\n第二段", output.get("rawSummary"));
        assertTrue(((List<?>) output.get("webResults")).isEmpty());
        assertEquals(0, output.get("resultCount"));
    }

    @Test
    @DisplayName("入参校验：query 空白 / 配置缺失 → IllegalArgumentException")
    void rejectsInvalidInput() {
        SearchInfinityMcpClient client = client();

        assertThrows(IllegalArgumentException.class,
                () -> client.webSearch(null, "查询", 5, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> client.webSearch(mcpConfig("custom-mcp"), "  ", 5, null, null, null));
    }
}
