package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.common.BoundedStreamReader;
import com.paiagent.common.UrlSafetyValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgnesVideoClient {

    private static final int DEFAULT_FRAME_RATE = 24;
    private static final int DEFAULT_DURATION_SECONDS = 5;
    private static final int MAX_NUM_FRAMES = 441;

    /**
     * 响应体读取上限（16MB）：JSON 响应/错误体超过此值即中止，避免恶意或异常端点
     * 返回超大响应体打爆内存（替代无上限的 {@code readAllBytes()}）。
     */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    public String createVideoTask(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl,
                                  int duration, String resolution, String ratio, String cameraMotion) throws IOException {
        JSONObject request = buildCreateRequest(config, prompt, referenceImageUrl, duration);
        JSONObject response = postJson(config, normalizeCreateEndpoint(config.apiUrl()), request);
        return findString(
                response,
                "video_id",
                "videoId",
                "task_id",
                "taskId",
                "id"
        );
    }

    public Map<String, Object> getVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        JSONObject response = getJson(config, normalizeQueryEndpoint(config.apiUrl(), taskId, normalizeModel(config.model())));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", firstText(findString(response, "status", "task_status"), "running"));
        Object progress = findValue(response, "progress", "percent");
        if (progress != null) {
            output.put("progress", progress);
        }
        output.put("videoUrl", findString(response,
                "video_url",
                "videoUrl",
                "url",
                "remixed_from_video_id"
        ));
        output.put("coverUrl", findString(response, "cover_url", "coverUrl", "poster_url", "first_frame_url", "last_frame_url"));
        output.put("raw", response);
        return output;
    }

    JSONObject buildCreateRequest(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl, int duration) {
        JSONObject request = new JSONObject();
        request.put("model", normalizeModel(config.model()));
        request.put("prompt", prompt);
        if (StringUtils.hasText(referenceImageUrl)) {
            request.put("image", referenceImageUrl.trim());
        }
        request.put("num_frames", normalizeNumFrames(duration, DEFAULT_FRAME_RATE));
        request.put("frame_rate", DEFAULT_FRAME_RATE);
        return request;
    }

    String normalizeCreateEndpoint(String apiUrl) {
        return normalizeBaseUrl(apiUrl) + "/v1/videos";
    }

    String normalizeQueryEndpoint(String apiUrl, String videoId, String model) {
        String base = normalizeBaseUrl(apiUrl);
        if (StringUtils.hasText(videoId) && videoId.trim().startsWith("video_")) {
            StringBuilder query = new StringBuilder(base)
                    .append("/agnesapi?video_id=")
                    .append(urlEncode(videoId.trim()));
            if (StringUtils.hasText(model)) {
                query.append("&model_name=").append(urlEncode(model.trim()));
            }
            return query.toString();
        }
        return base + "/v1/videos/" + urlEncode(videoId == null ? "" : videoId.trim());
    }

    int normalizeNumFrames(int duration, int frameRate) {
        int safeDuration = duration > 0 ? duration : DEFAULT_DURATION_SECONDS;
        int safeFrameRate = frameRate > 0 ? frameRate : DEFAULT_FRAME_RATE;
        int candidate = safeDuration * safeFrameRate + 1;
        candidate = Math.max(9, Math.min(MAX_NUM_FRAMES, candidate));
        int normalized = Math.round((candidate - 1) / 8.0f) * 8 + 1;
        if (normalized > MAX_NUM_FRAMES) {
            normalized = MAX_NUM_FRAMES;
        }
        return Math.max(9, normalized);
    }

    String normalizeModel(String model) {
        if (!StringUtils.hasText(model)) {
            return model;
        }
        String normalized = model.trim();
        String lower = normalized.toLowerCase();
        return switch (lower) {
            case "agnes-video-2.0", "agnes-video-v20", "agnes-video-v2" -> "agnes-video-v2.0";
            default -> normalized;
        };
    }

    private JSONObject postJson(ResolvedAgentPlanConfig config, String endpoint, JSONObject body) throws IOException {
        HttpURLConnection conn = openConnection(endpoint, "POST", config.apiKey());
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.getOutputStream().write(bytes);
        return readResponse(conn);
    }

    private JSONObject getJson(ResolvedAgentPlanConfig config, String endpoint) throws IOException {
        HttpURLConnection conn = openConnection(endpoint, "GET", config.apiKey());
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(String endpoint, String method, String apiKey) throws IOException {
        // 迭代10 P1：apiUrl 来自节点/全局配置（导入的工作流即可携带任意值），未校验时
        // 会把 API Key 发往内网/云元数据端点——与 SafeHttpFetcher / TTS / LLM 同一出站策略
        UrlSafetyValidator.assertSafeOutbound(endpoint);
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        return conn;
    }

    private JSONObject readResponse(HttpURLConnection conn) throws IOException {
        String requestUrl = conn.getURL().toString();
        int status = conn.getResponseCode();
        // 迭代10 P1：限长读取替代 readAllBytes()，并确保连接释放
        // （修复前流未关闭、conn 未 disconnect，keep-alive 连接会泄漏到 GC）
        try (InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()) {
            byte[] bytes = BoundedStreamReader.readCapped(is, MAX_RESPONSE_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new IOException("Agnes 视频生成调用失败, endpoint=" + requestUrl + ", HTTP " + status + ": " + text);
            }
            return JSON.parseObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private String normalizeBaseUrl(String apiUrl) {
        String base = StringUtils.hasText(apiUrl) ? apiUrl.trim() : "https://apihub.agnes-ai.com";
        base = rewriteLegacyAgnesHost(base);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String normalized = normalizeFromVersionedPath(base);
        if (normalized != null) {
            return normalized;
        }
        return base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
    }

    private String rewriteLegacyAgnesHost(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl);
            if ("api.agnes-ai.com".equalsIgnoreCase(uri.getHost())) {
                URI normalized = new URI(
                        uri.getScheme(),
                        uri.getUserInfo(),
                        "apihub.agnes-ai.com",
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        uri.getFragment()
                );
                return normalized.toString();
            }
        } catch (Exception ignored) {
            return apiUrl;
        }
        return apiUrl;
    }

    private String normalizeFromVersionedPath(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getRawAuthority())) {
                return null;
            }

            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return null;
            }

            String lowerPath = path.toLowerCase();
            int versionIndex = lowerPath.indexOf("/v1");
            if (versionIndex < 0) {
                return null;
            }

            String prefix = path.substring(0, versionIndex);
            return uri.getScheme() + "://" + uri.getRawAuthority() + prefix;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String findString(JSONObject object, String... keys) {
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

    private Object findValue(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            if (value != null) {
                return value;
            }
        }
        for (Object value : object.values()) {
            if (value instanceof JSONObject child) {
                Object found = findValue(child, keys);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
