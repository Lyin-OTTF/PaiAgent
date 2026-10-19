package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiCompatibleImageClient {

    /**
     * 响应体读取上限（16MB）：兼容 {@code response_format=b64_json} 的内联图片
     * （大图 base64 可达数 MB），同时避免恶意/异常端点用超大响应体打爆内存。
     */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    public Map<String, Object> generateImage(ResolvedAgentPlanConfig config, String prompt,
                                             String referenceImageUrl, String size, int count,
                                             String negativePrompt, String style) throws IOException {
        JSONObject request = buildRequest(config, prompt, referenceImageUrl, size, count, negativePrompt, style);
        JSONObject response = postJson(config, request);
        List<String> urls = new ArrayList<>();
        JSONArray data = response.getJSONArray("data");
        if (data != null) {
            for (Object item : data) {
                if (item instanceof JSONObject object) {
                    String url = object.getString("url");
                    if (StringUtils.hasText(url)) {
                        urls.add(url);
                        continue;
                    }

                    String b64Json = object.getString("b64_json");
                    if (StringUtils.hasText(b64Json)) {
                        urls.add("data:image/png;base64," + b64Json);
                    }
                }
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("imageUrls", urls);
        output.put("metadata", response);
        return output;
    }

    JSONObject buildRequest(ResolvedAgentPlanConfig config, String prompt,
                            String referenceImageUrl, String size, int count,
                            String negativePrompt, String style) {
        JSONObject request = new JSONObject();
        request.put("model", config.model());
        request.put("prompt", normalizePrompt(prompt, negativePrompt, style));
        if (StringUtils.hasText(size)) {
            request.put("size", size.trim());
        }

        if (isAgnesProvider(config.provider())) {
            JSONObject extraBody = new JSONObject();
            extraBody.put("response_format", "url");
            if (StringUtils.hasText(referenceImageUrl)) {
                JSONArray images = new JSONArray();
                images.add(referenceImageUrl.trim());
                extraBody.put("image", images);
            }
            request.put("extra_body", extraBody);
            return request;
        }

        request.put("response_format", "url");
        request.put("n", Math.max(1, Math.min(count, 4)));
        if (StringUtils.hasText(referenceImageUrl)) {
            request.put("image", referenceImageUrl.trim());
        }
        return request;
    }

    private JSONObject postJson(ResolvedAgentPlanConfig config, JSONObject body) throws IOException {
        HttpURLConnection conn = openConnection(config);
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.getOutputStream().write(bytes);
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(ResolvedAgentPlanConfig config) throws IOException {
        String endpoint = normalizeImageEndpoint(config.apiUrl(), config.provider());
        // 迭代10 P1：apiUrl 来自节点/全局配置（导入的工作流即可携带任意值），未校验时
        // 会把 API Key 发往内网/云元数据端点——与 SafeHttpFetcher / TTS / LLM 同一出站策略
        UrlSafetyValidator.assertSafeOutbound(endpoint);
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        return conn;
    }

    private JSONObject readResponse(HttpURLConnection conn) throws IOException {
        String requestUrl = conn.getURL().toString();
        int status = conn.getResponseCode();
        // 迭代10 P1：限长读取替代 readAllBytes()，并确保连接释放
        try (InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()) {
            byte[] bytes = BoundedStreamReader.readCapped(is, MAX_RESPONSE_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new IOException("OpenAI 兼容图片生成调用失败, endpoint=" + requestUrl + ", HTTP " + status + ": " + text);
            }
            return JSON.parseObject(text);
        } finally {
            conn.disconnect();
        }
    }

    String normalizeImageEndpoint(String apiUrl) {
        return normalizeImageEndpoint(apiUrl, null);
    }

    String normalizeImageEndpoint(String apiUrl, String provider) {
        String base = StringUtils.hasText(apiUrl) ? apiUrl.trim() : "https://api.openai.com/v1";
        if (isAgnesProvider(provider)) {
            base = normalizeAgnesBaseUrl(base);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/images/generations")) {
            return base;
        }

        String endpointFromVersionedPath = normalizeFromVersionedPath(base);
        if (endpointFromVersionedPath != null) {
            return endpointFromVersionedPath;
        }

        if (!base.endsWith("/v1")) {
            base = base + "/v1";
        }
        return base + "/images/generations";
    }

    private String normalizeAgnesBaseUrl(String apiUrl) {
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
            return uri.getScheme() + "://" + uri.getRawAuthority() + prefix + "/v1/images/generations";
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizePrompt(String prompt, String negativePrompt, String style) {
        String normalized = prompt.trim();
        if (StringUtils.hasText(style)) {
            normalized = normalized + "\nStyle: " + style.trim();
        }
        if (StringUtils.hasText(negativePrompt)) {
            normalized = normalized + "\nNegative prompt: " + negativePrompt.trim();
        }
        return normalized;
    }

    private boolean isAgnesProvider(String provider) {
        return StringUtils.hasText(provider) && "agnes".equals(provider.trim().toLowerCase());
    }
}
