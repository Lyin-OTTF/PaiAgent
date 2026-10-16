package com.paiagent.engine.agent.tool;

import com.paiagent.common.SafeHttpFetcher;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页读取工具（LLM 可直接调用）。
 *
 * <p>迭代7（评审报告 A4 边界闭合）：修复前与 {@code WebFetchNodeExecutor} 各自实现抓取，
 * 安全加固只补了节点执行器，本工具的 {@code openConnection().readAllBytes()} 仍可被模型
 * 诱导访问内网/云元数据（无协议校验、自动跟随重定向、无大小上限）。现统一收敛到
 * {@link SafeHttpFetcher}：逐跳安全校验 + 超时 + 限长。
 */
@Component
@ToolPolicy(level = AutonomyLevel.OBSERVE)
public class WebFetchTool implements AgentTool {

    private static final int MAX_PAGE_CHARS = 8000;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "读取指定 URL 的网页内容，用于搜索结果后的原文核验和信息抽取。";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"urls\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"url\":{\"type\":\"string\"}},\"required\":[]}";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, AgentToolContext context) throws Exception {
        List<String> urls = resolveUrls(arguments);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("网页读取工具缺少 url 或 urls 参数");
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (String url : urls) {
            String text = SafeHttpFetcher.fetchText(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_BYTES);
            String truncated = text.length() > MAX_PAGE_CHARS ? text.substring(0, MAX_PAGE_CHARS) : text;

            Map<String, Object> page = new LinkedHashMap<>();
            page.put("url", url);
            page.put("content", truncated);
            pages.add(page);

            content.append("URL: ").append(url).append("\n").append(truncated).append("\n\n");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("pages", pages);
        output.put("content", content.toString().trim());
        output.put("citations", pages.stream().map(page -> page.get("url")).toList());
        return output;
    }

    private List<String> resolveUrls(Map<String, Object> arguments) {
        List<String> urls = new ArrayList<>();
        Object urlsValue = arguments.get("urls");
        if (urlsValue instanceof List<?> list) {
            for (Object item : list) {
                addUrl(urls, item);
            }
        } else {
            addUrl(urls, urlsValue);
        }
        addUrl(urls, arguments.get("url"));
        return urls.stream().distinct().toList();
    }

    private void addUrl(List<String> urls, Object value) {
        if (value == null) {
            return;
        }
        for (String url : value.toString().split("[,，\\n\\s]+")) {
            String trimmed = url.trim();
            if (!trimmed.isBlank()) {
                urls.add(trimmed);
            }
        }
    }
}
