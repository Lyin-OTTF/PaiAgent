package com.paiagent.engine.executor.impl;

import com.paiagent.common.SafeHttpFetcher;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页读取节点执行器。
 *
 * <p>迭代7（评审报告 A4）：出站抓取补齐 SSRF 防护与资源上限——
 * 修复前 {@code URI.create(url).toURL().openStream().readAllBytes()} 无协议/内网校验、
 * 无连接与读取超时、无大小上限，可被上游节点或模型输出诱导访问内网与云元数据，
 * 也可能被超大响应拖垮内存。安全抓取逻辑统一收敛在 {@link SafeHttpFetcher}。
 */
@Component
public class WebFetchNodeExecutor extends AbstractAgentPlanNodeExecutor {

    /** 单页正文截断长度（进入提示词的字符上限）。 */
    private static final int MAX_CONTENT_CHARS = 8000;

    /** 单页读取字节上限，防止超大响应 OOM。 */
    private static final int MAX_BYTES = 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        String urlsText = textValue(node, input, "urls", "url");
        if (urlsText == null) {
            throw new IllegalArgumentException("网页读取节点缺少 urls");
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (String urlText : urlsText.split("[,，\\n\\s]+")) {
            if (urlText.isBlank()) {
                continue;
            }
            String text = fetchText(urlText.trim());
            String truncated = text.length() > MAX_CONTENT_CHARS ? text.substring(0, MAX_CONTENT_CHARS) : text;
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("url", urlText.trim());
            page.put("content", truncated);
            pages.add(page);
            content.append("URL: ").append(urlText.trim()).append("\n").append(truncated).append("\n\n");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("pages", pages);
        output.put("content", content.toString().trim());
        output.put("citations", pages.stream().map(page -> page.get("url")).toList());
        output.put("output", content.toString().trim());
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "web_fetch";
    }

    /** 委托 {@link SafeHttpFetcher}：每跳安全校验 + 手动重定向 + 超时 + 限长。 */
    private String fetchText(String urlText) throws IOException {
        return SafeHttpFetcher.fetchText(urlText, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_BYTES);
    }
}
