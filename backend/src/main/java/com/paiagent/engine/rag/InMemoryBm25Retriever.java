package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 迭代3 · 内存 BM25 检索路：全量语料的词法检索，不依赖数据库。
 * <p>用途：① 离线评测（评测集 + 语料在内存中跑混合检索指标）；
 * ② FULLTEXT 索引不可用时的降级路径。打分与 {@link Bm25Retriever} 共用 {@link Bm25Scorer}，口径一致。
 */
public class InMemoryBm25Retriever implements Retriever {

    private final ChineseAnalyzer analyzer = ChineseAnalyzer.shared();
    private final Bm25Scorer scorer = new Bm25Scorer();
    private final List<ScoredChunk> corpus;

    public InMemoryBm25Retriever(List<ScoredChunk> corpus) {
        this.corpus = corpus == null ? List.of() : List.copyOf(corpus);
    }

    @Override
    public List<ScoredChunk> retrieve(Long knowledgeBaseId, String query, int topK) {
        if (topK <= 0 || query == null || query.isBlank() || corpus.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = new ArrayList<>(analyzer.terms(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        int corpusSize = corpus.size();
        double avgDocLength = corpus.stream()
                .mapToInt(chunk -> analyzer.normalizedLength(chunk.content()))
                .average()
                .orElse(1.0);
        Map<String, Long> docFreq = docFrequencies();
        Function<String, Double> idfLookup = term -> scorer.idf(corpusSize, docFreq.getOrDefault(term, 0L).intValue());

        List<ScoredChunk> scored = new ArrayList<>();
        for (ScoredChunk chunk : corpus) {
            Map<String, Integer> docTermFreq = analyzer.termFrequencies(chunk.content());
            double score = scorer.totalScore(queryTerms, docTermFreq,
                    analyzer.normalizedLength(chunk.content()), avgDocLength, idfLookup);
            if (score > 0) {
                scored.add(chunk.withScore(score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
    }

    /**
     * 全语料词项文档频率（df）：每次调用重算而非缓存——评测场景语料小且一次性，
     * 与 DB 路缓存在一致性上的取舍不同，这里选择绝对一致。
     */
    private Map<String, Long> docFrequencies() {
        Map<String, Long> docFreq = new java.util.LinkedHashMap<>();
        for (ScoredChunk chunk : corpus) {
            for (String term : analyzer.termFrequencies(chunk.content()).keySet()) {
                docFreq.merge(term, 1L, Long::sum);
            }
        }
        return docFreq;
    }
}
