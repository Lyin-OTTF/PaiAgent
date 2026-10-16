package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for Agent Plan capabilities. Endpoint details stay here instead of node executors.
 *
 * <p>迭代8（评审报告 P2 #11 可测性改造）：HTTP 交换抽为可注入的 {@link HttpTransport}
 * （生产默认走 {@link #httpExchange}，即原有 HttpURLConnection 实现，超时等行为不变），
 * 使「URL 拼装 / 请求体构造 / 响应解析 / 非 2xx 处理」可在无网络环境下单测。
 */
@Slf4j
@Service
public class VolcengineAgentPlanClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    /** HTTP 交换结果：状态码 + 响应正文。 */
    record HttpResponse(int status, String body) {
    }

    /**
     * 传输层抽象：执行一次 HTTP 交换。
     *
     * <p>生产默认实现 {@link #httpExchange}；测试注入假实现即可验证请求构造与响应解析，
     * 无需真实网络与供应商凭证。
     */
    @FunctionalInterface
    interface HttpTransport {
        HttpResponse exchange(String url, String method, Map<String, String> headers, String body)
                throws IOException;
    }

    private final HttpTransport transport;

    /** Spring 使用：默认传输层（HttpURLConnection，超时与既有行为一致）。 */
    public VolcengineAgentPlanClient() {
        this(VolcengineAgentPlanClient::httpExchange);
    }

    /** 测试使用：注入自定义传输层。 */
    VolcengineAgentPlanClient(HttpTransport transport) {
        this.transport = transport;
    }

    /** 生产传输层：HttpURLConnection 单次交换，不跟随重定向（由调用方语义决定重试）。 */
    private static HttpResponse httpExchange(String url, String method, Map<String, String> headers, String body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            headers.forEach(conn::setRequestProperty);
            if (body != null) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResponse(status, text);
        } finally {
            conn.disconnect();
        }
    }

    public List<Double> createEmbedding(ResolvedAgentPlanConfig config, String input) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());
        request.put("input", input);

        JSONObject response = postJson(config, "/embeddings", request);
        JSONArray data = response.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        JSONArray embedding = data.getJSONObject(0).getJSONArray("embedding");
        if (embedding == null) {
            return List.of();
        }

        List<Double> vector = new ArrayList<>(embedding.size());
        for (Object value : embedding) {
            if (value instanceof Number number) {
                vector.add(number.doubleValue());
            }
        }
        return vector;
    }

    public Map<String, Object> generateImage(ResolvedAgentPlanConfig config, String prompt,
                                             String referenceImageUrl, String size, int count,
                                             String negativePrompt, String style) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());
        // 迭代8：修复 negativePrompt/style 被静默丢弃——节点 executor 一直下传这两个参数，
        // 但本实现从未写入请求（另两个图像客户端都会消费）。与 OpenAiCompatibleImageClient
        // 同一约定折进 prompt 文本，避免向供应商发送其不识别的字段。
        request.put("prompt", composePrompt(prompt, negativePrompt, style));
        request.put("response_format", "url");
        request.put("output_format", "png");
        request.put("watermark", false);
        request.put("stream", false);
        if (StringUtils.hasText(size)) {
            request.put("size", size);
        }
        if (StringUtils.hasText(referenceImageUrl)) {
            request.put("image", referenceImageUrl);
        }

        JSONObject response = postJson(config, "/images/generations", request);
        List<String> urls = new ArrayList<>();
        JSONArray data = response.getJSONArray("data");
        if (data != null) {
            for (Object item : data) {
                if (item instanceof JSONObject object) {
                    String url = object.getString("url");
                    if (StringUtils.hasText(url)) {
                        urls.add(url);
                    }
                }
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("imageUrls", urls);
        output.put("metadata", response);
        return output;
    }

    public String createVideoTask(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl,
                                  int duration, String resolution, String ratio, String cameraMotion) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());

        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.add(textContent);
        if (StringUtils.hasText(referenceImageUrl)) {
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", referenceImageUrl);

            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageUrl);
            content.add(imageContent);
        }
        request.put("content", content);

        request.put("generate_audio", true);
        request.put("duration", duration);
        if (StringUtils.hasText(resolution)) {
            request.put("resolution", resolution);
        }
        if (StringUtils.hasText(ratio)) {
            request.put("ratio", ratio);
        }
        request.put("watermark", false);

        JSONObject response = postJson(config, "/contents/generations/tasks", request);
        return firstText(response.getString("id"), response.getString("task_id"), response.getString("taskId"));
    }

    public Map<String, Object> getVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        JSONObject response = getJson(config, "/contents/generations/tasks/" + taskId);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", firstText(response.getString("status"), response.getString("task_status"), "running"));
        output.put("videoUrl", findString(response, "video_url", "videoUrl", "url"));
        output.put("coverUrl", findString(response, "last_frame_url", "cover_url", "coverUrl", "poster_url"));
        output.put("raw", response);
        return output;
    }

    private JSONObject postJson(ResolvedAgentPlanConfig config, String path, JSONObject body) throws IOException {
        return parseResponse(transport.exchange(
                buildUrl(config, path), "POST", authHeaders(config), body.toJSONString()));
    }

    private JSONObject getJson(ResolvedAgentPlanConfig config, String path) throws IOException {
        return parseResponse(transport.exchange(
                buildUrl(config, path), "GET", authHeaders(config), null));
    }

    private static String buildUrl(ResolvedAgentPlanConfig config, String path) {
        return normalizeBaseUrl(config.apiUrl()) + normalizePath(path);
    }

    private static Map<String, String> authHeaders(ResolvedAgentPlanConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.apiKey());
        return headers;
    }

    /**
     * 非 2xx 统一转 IOException（带状态码与响应体，便于定位供应商错误）；
     * 2xx 但空body 返回空 JSON 对象（修复：此前 {@code JSON.parseObject("")} 返回 null 会导致调用方 NPE）。
     */
    private static JSONObject parseResponse(HttpResponse response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Agent Plan 调用失败, HTTP " + response.status() + ": " + response.body());
        }
        if (!StringUtils.hasText(response.body())) {
            return new JSONObject();
        }
        return JSON.parseObject(response.body());
    }

    /**
     * 将 negativePrompt/style 折进 prompt（与 {@code OpenAiCompatibleImageClient} 同一约定）。
     * 不发送供应商可能不认识的独立字段，避免严格校验的 API 直接 400。
     */
    static String composePrompt(String prompt, String negativePrompt, String style) {
        StringBuilder builder = new StringBuilder(prompt == null ? "" : prompt.trim());
        if (StringUtils.hasText(style)) {
            builder.append("\nStyle: ").append(style.trim());
        }
        if (StringUtils.hasText(negativePrompt)) {
            builder.append("\nNegative prompt: ").append(negativePrompt.trim());
        }
        return builder.toString();
    }

    private static String normalizeBaseUrl(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String findString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        for (Object value : object.values()) {
            if (value instanceof JSONObject child) {
                String found = findString(child, keys);
                if (StringUtils.hasText(found)) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

}
