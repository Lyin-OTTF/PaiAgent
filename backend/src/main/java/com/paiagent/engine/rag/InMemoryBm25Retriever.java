package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 迭代3 · 内存 BM25 检索路：全量语料的词法检索，不依赖数据库。
 * <p>用途：① 离线评测（评测集 + 语料在内存中跑混合检索指标）；
 * ② FULLTEXT 索引不可用时的降级路径。打分与 {@link Bm25Retriever} 共用 {@link Bm25Scorer}，口径一致。
 *
 * <p>迭代7（评审报告 #17）：语料在构造时已 {@code List.copyOf} 固化，不可能被外部修改，
 * 因此词项文档频率、各文档词频与平均文档长度改为<b>惰性构建一次的只读索引</b>——
 * 原先每次检索都全量重算 O(N×词数)，在作为降级路径的大语料下明显拖慢，且"每次重算"换来的
 * 一致性保证是多余的（语料本就不可变）。
 */
public class InMemoryBm25Retriever implements Retriever {

    private final ChineseAnalyzer analyzer = ChineseAnalyzer.shared();
    private final Bm25Scorer scorer = new Bm25Scorer();
    private final List<ScoredChunk> corpus;

    /** 惰性构建的语料索引；volatile 保证可见性，竞态下最多重复构建一次（结果等价）。 */
    private volatile CorpusIndex corpusIndex;

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
        CorpusIndex index = corpusIndex();
        Function<String, Double> idfLookup = term -> scorer.idf(index.corpusSize(), index.docFrequency(term));

        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < index.corpusSize(); i++) {
            double score = scorer.totalScore(queryTerms, index.termFrequencies(i),
                    index.normalizedLength(i), index.avgDocLength(), idfLookup);
            if (score > 0) {
                scored.add(corpus.get(i).withScore(score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
    }

    private CorpusIndex corpusIndex() {
        CorpusIndex local = corpusIndex;
        if (local == null) {
            local = CorpusIndex.build(corpus, analyzer);
            corpusIndex = local;
        }
        return local;
    }

    /** 语料级只读索引：逐文档词频、归一化长度、文档频率与平均文档长度，构建一次复用。 */
    private static final class CorpusIndex {

        private final List<Map<String, Integer>> termFrequencies;
        private final int[] normalizedLengths;
        private final Map<String, Long> docFrequencies;
        private final double avgDocLength;

        private CorpusIndex(List<Map<String, Integer>> termFrequencies,
                            int[] normalizedLengths,
                            Map<String, Long> docFrequencies,
                            double avgDocLength) {
            this.termFrequencies = termFrequencies;
            this.normalizedLengths = normalizedLengths;
            this.docFrequencies = docFrequencies;
            this.avgDocLength = avgDocLength;
        }

        static CorpusIndex build(List<ScoredChunk> corpus, ChineseAnalyzer analyzer) {
            List<Map<String, Integer>> termFrequencies = new ArrayList<>(corpus.size());
            int[] normalizedLengths = new int[corpus.size()];
            Map<String, Long> docFrequencies = new LinkedHashMap<>();
            long totalLength = 0L;
            for (int i = 0; i < corpus.size(); i++) {
                String content = corpus.get(i).content();
                Map<String, Integer> frequencies = analyzer.termFrequencies(content);
                termFrequencies.add(frequencies);
                int length = analyzer.normalizedLength(content);
                normalizedLengths[i] = length;
                totalLength += length;
                for (String term : frequencies.keySet()) {
                    docFrequencies.merge(term, 1L, Long::sum);
                }
            }
            double avgDocLength = corpus.isEmpty() ? 1.0 : (double) totalLength / corpus.size();
            if (avgDocLength <= 0) {
                avgDocLength = 1.0;
            }
            return new CorpusIndex(termFrequencies, normalizedLengths, docFrequencies, avgDocLength);
        }

        int corpusSize() {
            return normalizedLengths.length;
        }

        Map<String, Integer> termFrequencies(int index) {
            return termFrequencies.get(index);
        }

        int normalizedLength(int index) {
            return normalizedLengths[index];
        }

        int docFrequency(String term) {
            return docFrequencies.getOrDefault(term, 0L).intValue();
        }

        double avgDocLength() {
            return avgDocLength;
        }
    }
}
