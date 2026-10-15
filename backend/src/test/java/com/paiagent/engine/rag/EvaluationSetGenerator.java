package com.paiagent.engine.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.paiagent.engine.rag.CorpusFixture.Doc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代3 评测集生成工具：从 {@link CorpusFixture} 领域语料派生查询，
 * 落盘 {@code rag/corpus.json} 与 {@code rag/evaluation-set.json} 到主资源目录。
 * <p>派生规则：每篇文档按标题生成三个自然语言查询，相关集严格为来源文档；
 * 同主题文档共享术语形成干扰，保证指标可区分检索策略优劣。
 * <p>{@link #ensureGenerated()} 供只读测试在资源缺失时自动生成，消除测试间顺序依赖。
 */
final class EvaluationSetGenerator {

    private static final Path RESOURCES_DIR = Paths.get("src/main/resources/rag");

    private EvaluationSetGenerator() {
    }

    static int generate() throws Exception {
        List<Doc> docs = CorpusFixture.docs();
        List<Map<String, Object>> corpus = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();

        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            long docId = i + 1L;
            Map<String, Object> corpusItem = new LinkedHashMap<>();
            corpusItem.put("id", docId);
            corpusItem.put("topic", doc.topic());
            corpusItem.put("title", doc.title());
            corpusItem.put("content", doc.content());
            corpus.add(corpusItem);

            for (String query : queriesFor(doc.title())) {
                Map<String, Object> evaluationCase = new LinkedHashMap<>();
                evaluationCase.put("query", query);
                evaluationCase.put("relevantChunkIds", List.of(docId));
                evaluationCase.put("topic", doc.topic());
                evaluationCase.put("note", "generated-from-doc-" + docId);
                cases.add(evaluationCase);
            }
        }

        Files.createDirectories(RESOURCES_DIR);
        Files.writeString(RESOURCES_DIR.resolve("corpus.json"),
                JSON.toJSONString(Map.of("docs", corpus), JSONWriter.Feature.PrettyFormat),
                StandardCharsets.UTF_8);
        Files.writeString(RESOURCES_DIR.resolve("evaluation-set.json"),
                JSON.toJSONString(Map.of("cases", cases), JSONWriter.Feature.PrettyFormat),
                StandardCharsets.UTF_8);
        return cases.size();
    }

    /**
     * 资源缺失时自动生成（工作目录必须是 backend/，否则仅跳过由调用方断言）。
     */
    static void ensureGenerated() {
        boolean missing = EvaluationSet.class.getClassLoader().getResource("rag/evaluation-set.json") == null
                || EvaluationSet.class.getClassLoader().getResource("rag/corpus.json") == null;
        if (!missing) {
            return;
        }
        try {
            generate();
        } catch (Exception e) {
            throw new IllegalStateException("评测集资源缺失且自动生成失败: " + e.getMessage(), e);
        }
    }

    private static List<String> queriesFor(String title) {
        return List.of(
                "什么是" + title,
                title + "怎么用",
                title + "有什么作用"
        );
    }
}
