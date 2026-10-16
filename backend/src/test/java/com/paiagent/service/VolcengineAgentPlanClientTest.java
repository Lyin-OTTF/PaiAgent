package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代8（评审报告 P2 #11）：{@link VolcengineAgentPlanClient} 单测。
 *
 * <p>该类此前零测试（需真实网络 + 供应商凭证）。改造为可注入 {@link VolcengineAgentPlanClient.HttpTransport}
 * 后，请求构造（URL 拼装 / 请求体 / 鉴权头）与响应解析（embedding / imageUrls / taskId / 视频任务字段）
 * 均可在无网络环境验证。测试同时锁定迭代8 修复的两处缺陷：negativePrompt/style 静默丢弃、
 * 2xx 空 body 导致 NPE。
 */
class VolcengineAgentPlanClientTest {

    private record CapturedRequest(String url, String method, Map<String, String> headers, String body) {
    }

    private final List<CapturedRequest> requests = new ArrayList<>();
    private String nextResponseBody = "{}";
    private int nextStatus = 200;

    private VolcengineAgentPlanClient clientWithNext(String responseBody, int status) {
        this.nextResponseBody = responseBody;
        this.nextStatus = status;
        return new VolcengineAgentPlanClient((url, method, headers, body) -> {
            requests.add(new CapturedRequest(url, method, new LinkedHashMap<>(headers), body));
            return new VolcengineAgentPlanClient.HttpResponse(nextStatus, nextResponseBody);
        });
    }

    private VolcengineAgentPlanClient clientWith(String responseBody) {
        return clientWithNext(responseBody, 200);
    }

    private CapturedRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    private static ResolvedAgentPlanConfig config() {
        return new ResolvedAgentPlanConfig(
                1L, "volcengine_agent_plan", "https://ark.example.com/api/v1/", "key-123",
                "ep-model", "emb-model", "img-model", "vid-model", false);
    }

    @Test
    @DisplayName("URL 拼装：apiUrl 去尾斜杠与 /v1 后缀，再接端点路径")
    void buildsUrlFromNormalizedBaseAndPath() throws IOException {
        VolcengineAgentPlanClient client = clientWith("{\"data\":[{\"embedding\":[0.1,0.2]}]}");

        client.createEmbedding(config(), "你好");

        assertEquals("https://ark.example.com/api/embeddings", lastRequest().url());
        assertEquals("POST", lastRequest().method());
        assertEquals("Bearer key-123", lastRequest().headers().get("Authorization"));
        assertEquals("application/json", lastRequest().headers().get("Content-Type"));
        JSONObject body = JSON.parseObject(lastRequest().body());
        assertEquals("ep-model", body.getString("model"));
        assertEquals("你好", body.getString("input"));
    }

    @Test
    @DisplayName("GET 请求不带 body，路径同样归一化")
    void getRequestHasNoBody() throws IOException {
        VolcengineAgentPlanClient client = clientWith("{\"status\":\"succeeded\"}");

        client.getVideoTask(config(), "task-9");

        assertEquals("https://ark.example.com/api/contents/generations/tasks/task-9", lastRequest().url());
        assertEquals("GET", lastRequest().method());
        assertNull(lastRequest().body());
    }

    @Test
    @DisplayName("embedding 解析：数值数组转 Double 列表，非数值项跳过")
    void parsesEmbeddingVector() throws IOException {
        VolcengineAgentPlanClient client = clientWith("{\"data\":[{\"embedding\":[0.5,2,\"x\",null]}]}");

        List<Double> vector = client.createEmbedding(config(), "q");

        assertEquals(List.of(0.5, 2.0), vector);
    }

    @Test
    @DisplayName("embedding 解析：data 缺失/为空、embedding 缺失时返回空列表")
    void returnsEmptyEmbeddingForMissingData() throws IOException {
        assertEquals(List.of(), clientWith("{}").createEmbedding(config(), "q"));
        assertEquals(List.of(), clientWith("{\"data\":[]}").createEmbedding(config(), "q"));
        assertEquals(List.of(), clientWith("{\"data\":[{}]}").createEmbedding(config(), "q"));
    }

    @Test
    @DisplayName("图像生成：negativePrompt/style 折进 prompt（迭代8 修复静默丢弃）")
    void foldsNegativePromptAndStyleIntoPrompt() throws IOException {
        VolcengineAgentPlanClient client = clientWith("{\"data\":[{\"url\":\"https://img/1.png\"}]}");

        client.generateImage(config(), "一只猫", "https://ref/1.png", "2K", 2, "模糊", "动漫");

        JSONObject body = JSON.parseObject(lastRequest().body());
        assertEquals("一只猫\nStyle: 动漫\nNegative prompt: 模糊", body.getString("prompt"));
        assertEquals("2K", body.getString("size"));
        assertEquals("https://ref/1.png", body.getString("image"));
    }

    @Test
    @DisplayName("图像生成：size/referenceImageUrl 为空时不写入请求")
    void omitsBlankOptionalImageFields() throws IOException {
        VolcengineAgentPlanClient client = clientWith("{\"data\":[]}");

        client.generateImage(config(), "一只猫", "  ", "", 1, null, null);

        JSONObject body = JSON.parseObject(lastRequest().body());
        assertFalse(body.containsKey("size"));
        assertFalse(body.containsKey("image"));
        assertEquals("一只猫", body.getString("prompt"));
    }

    @Test
    @DisplayName("图像生成：提取 data[].url，跳过无 url 项，metadata 保留原始响应")
    void extractsImageUrls() throws IOException {
        VolcengineAgentPlanClient client =
                clientWith("{\"data\":[{\"url\":\"https://img/1.png\"},{\"foo\":\"bar\"},{\"url\":\"https://img/2.png\"}]}");

        Map<String, Object> output = client.generateImage(config(), "p", null, null, 1, null, null);

        assertEquals(List.of("https://img/1.png", "https://img/2.png"), output.get("imageUrls"));
        assertInstanceOf(JSONObject.class, output.get("metadata"));
    }

    @Test
    @DisplayName("视频任务创建：content 数组按参考图拼装，taskId 按 id/task_id/taskId 回退")
    void buildsVideoTaskRequestAndFallsBackTaskId() throws IOException {
        VolcengineAgentPlanClient withId = clientWith("{\"id\":\"t-1\"}");
        assertEquals("t-1", withId.createVideoTask(config(), "p", null, 5, "1080p", "16:9", null));

        VolcengineAgentPlanClient withTaskId = clientWith("{\"task_id\":\"t-2\"}");
        assertEquals("t-2", withTaskId.createVideoTask(config(), "p", "https://ref/2.png", 5, null, null, null));

        VolcengineAgentPlanClient withCamelId = clientWith("{\"taskId\":\"t-3\"}");
        assertEquals("t-3", withCamelId.createVideoTask(config(), "p", null, 5, null, null, null));

        // 请求体结构断言取「带参考图」的第二次调用（第三次未传参考图，content 仅文本一项）
        JSONObject body = JSON.parseObject(requests.get(1).body());
        assertEquals(2, body.getJSONArray("content").size());
        assertEquals("image_url", body.getJSONArray("content").getJSONObject(1).getString("type"));
        assertEquals("https://ref/2.png",
                body.getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertEquals(5, body.getIntValue("duration"));
        assertFalse(body.containsKey("resolution"), "空 resolution 不写入");
    }

    @Test
    @DisplayName("视频任务查询：status/videoUrl/coverUrl 多键回退（含嵌套查找）")
    void parsesVideoTaskStatusAndUrls() throws IOException {
        VolcengineAgentPlanClient client = clientWith(
                "{\"task_status\":\"succeeded\",\"result\":{\"video_url\":\"https://v/1.mp4\"},"
                        + "\"output\":{\"last_frame_url\":\"https://c/1.png\"}}");

        Map<String, Object> output = client.getVideoTask(config(), "t-1");

        assertEquals("succeeded", output.get("status"));
        assertEquals("https://v/1.mp4", output.get("videoUrl"));
        assertEquals("https://c/1.png", output.get("coverUrl"));
    }

    @Test
    @DisplayName("视频任务查询：无状态字段时回落 running")
    void defaultsVideoTaskStatusToRunning() throws IOException {
        Map<String, Object> output = clientWith("{}").getVideoTask(config(), "t-1");

        assertEquals("running", output.get("status"));
        assertNull(output.get("videoUrl"));
    }

    @Test
    @DisplayName("非 2xx 转 IOException，消息带状态码与响应体")
    void non2xxThrowsIOExceptionWithBody() {
        VolcengineAgentPlanClient client = clientWithNext("{\"error\":\"quota exceeded\"}", 429);

        IOException error = assertThrows(IOException.class, () -> client.createEmbedding(config(), "q"));

        assertTrue(error.getMessage().contains("HTTP 429"), error.getMessage());
        assertTrue(error.getMessage().contains("quota exceeded"), error.getMessage());
    }

    @Test
    @DisplayName("2xx 但响应体为空：返回空 JSON 而非 NPE（迭代8 修复）")
    void emptySuccessBodyDoesNotThrow() throws IOException {
        Map<String, Object> output = clientWith("").getVideoTask(config(), "t-1");

        assertEquals("running", output.get("status"));
        assertNull(output.get("videoUrl"));
    }
}
