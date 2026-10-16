package com.paiagent.engine.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代3 端到端评测：内置评测集（130+ 用例）跑通「内存 BM25 + HNSW 向量 + RRF 融合 + 重排」全链路，
 * 输出 Recall@K / MRR / 延迟指标，作为检索质量回归基线。
 * <p>对照策略同时输出纯 BM25 指标：混合检索不应显著低于词法单路（差异在噪声范围内即达标），
 * 而向量路补足了词法路对同义表述的盲区。
 */
class RagEndToEndEvaluationTest {

    @Test
    void shouldEvaluateHybridRetrievalOnBuiltInSet() {
        EvaluationSetGenerator.ensureGenerated();
        List<ScoredChunk> corpus = loadCorpus();
        EvaluationSet evaluationSet = EvaluationSet.loadFromClasspath();
        assertTrue(evaluationSet.cases().size() >= 130, "评测集应不少于 130 条");

        // 词法路：内存 BM25
        InMemoryBm25Retriever lexical = new InMemoryBm25Retriever(corpus);
        // 稠密路：mock Mapper 供 DenseRetriever 懒加载，embedding 用确定性哈希向量
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        when(chunkMapper.selectList(any())).thenReturn(toChunks(corpus));
        DenseRetriever dense = new DenseRetriever(chunkMapper, new HashingEmbeddingFunction(), new RagProperties());
        // 融合：默认权重与参数
        HybridRetriever hybrid = new HybridRetriever(lexical, dense, new Reranker(), new RagProperties());

        RagEvaluator evaluator = new RagEvaluator();
        RagEvaluator.Report hybridReport = evaluator.evaluate(
                evaluationSet.cases(), (baseId, query) -> hybrid.retrieve(1L, query, 10, 0), 5, 10);
        RagEvaluator.Report lexicalReport = evaluator.evaluate(
                evaluationSet.cases(), (baseId, query) -> lexical.retrieve(1L, query, 10), 5, 10);

        System.out.printf("[RAG-Eval] hybrid: Recall@5=%.3f Recall@10=%.3f MRR=%.3f hitRate=%d/%d latency mean=%.3fms p99=%.3fms%n",
                hybridReport.recallAtK().get(5), hybridReport.recallAtK().get(10), hybridReport.mrr(),
                hybridReport.hitCases(), hybridReport.caseCount(),
                hybridReport.meanLatencyMs(), hybridReport.p99LatencyMs());
        System.out.printf("[RAG-Eval] lexical-only: Recall@5=%.3f Recall@10=%.3f MRR=%.3f%n",
                lexicalReport.recallAtK().get(5), lexicalReport.recallAtK().get(10), lexicalReport.mrr());

        // 规约：混合检索 Recall@5 >= 0.6、MRR >= 0.5；不显著劣于词法单路（允许 5% 噪声）
        assertTrue(hybridReport.recallAtK().get(5) >= 0.6,
                "hybrid Recall@5 应 >= 0.6，实际 " + hybridReport.recallAtK().get(5));
        assertTrue(hybridReport.mrr() >= 0.5,
                "hybrid MRR 应 >= 0.5，实际 " + hybridReport.mrr());
        assertTrue(hybridReport.recallAtK().get(5) >= lexicalReport.recallAtK().get(5) - 0.05,
                "hybrid 不应显著劣于词法单路");
    }

    private List<ScoredChunk> loadCorpus() {
        try (InputStream in = RagEndToEndEvaluationTest.class.getClassLoader().getResourceAsStream("rag/corpus.json")) {
            if (in == null) {
                throw new IllegalStateException("corpus.json 不存在，请先运行 EvaluationSetGeneratorTest");
            }
            Map<String, Object> root = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            List<?> docs = root.get("docs") instanceof List<?> list ? list : List.of();
            List<ScoredChunk> corpus = new ArrayList<>(docs.size());
            for (Object raw : docs) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                corpus.add(new ScoredChunk(
                        ((Number) map.get("id")).longValue(),
                        1L,
                        ((Number) map.get("id")).longValue(),
                        String.valueOf(map.get("title")),
                        String.valueOf(map.get("content")),
                        null,
                        null,
                        0));
            }
            return corpus;
        } catch (Exception e) {
            throw new IllegalStateException("加载语料失败: " + e.getMessage(), e);
        }
    }

    private List<KnowledgeChunk> toChunks(List<ScoredChunk> corpus) {
        HashingEmbeddingFunction embeddingFunction = new HashingEmbeddingFunction();
        List<KnowledgeChunk> chunks = new ArrayList<>(corpus.size());
        for (ScoredChunk doc : corpus) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setId(doc.chunkId());
            chunk.setKnowledgeBaseId(1L);
            chunk.setDocumentId(doc.documentId());
            chunk.setTitle(doc.title());
            chunk.setContent(doc.content());
            chunk.setStatus("READY");
            chunk.setEmbedding(JSON.toJSONString(embeddingFunction.embed(1L, doc.title() + " " + doc.content())));
            chunks.add(chunk);
        }
        return chunks;
    }
}
