package com.paiagent.engine.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线评测集：{@code classpath:rag/evaluation-set.json}。
 * <p>格式：{@code {"cases":[{"query":"...","relevantChunkIds":[1,2],"topic":"...","note":"..."}]}}。
 * 与语料 {@code rag/corpus.json} 配套：评测时先把语料灌入检索路，再逐条执行。
 */
public record EvaluationSet(List<EvaluationCase> cases) {

    private static final String RESOURCE = "rag/evaluation-set.json";

    public static EvaluationSet loadFromClasspath() {
        try (InputStream in = EvaluationSet.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("评测集资源不存在: " + RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> root = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            List<?> rawCases = root.get("cases") instanceof List<?> list ? list : List.of();
            List<EvaluationCase> cases = new ArrayList<>(rawCases.size());
            for (Object raw : rawCases) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                List<Long> relevant = new ArrayList<>();
                Object rawIds = map.get("relevantChunkIds");
                if (rawIds instanceof List<?> ids) {
                    for (Object id : ids) {
                        if (id instanceof Number number) {
                            relevant.add(number.longValue());
                        }
                    }
                }
                Object query = map.get("query");
                cases.add(new EvaluationCase(
                        query == null ? "" : String.valueOf(query),
                        List.copyOf(relevant),
                        map.get("topic") == null ? null : String.valueOf(map.get("topic")),
                        map.get("note") == null ? null : String.valueOf(map.get("note"))));
            }
            return new EvaluationSet(List.copyOf(cases));
        } catch (Exception e) {
            throw new IllegalStateException("加载评测集失败: " + e.getMessage(), e);
        }
    }
}
