package com.paiagent.engine.rag;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * BM25 打分纯函数（Robertson/Sparck-Jones 变体）。
 * <p>只做数学计算，不碰 IO：语料统计（N/df/avgdl）由调用方查询后经参数注入，
 * 便于单测验证饱和性、IDF 单调性、长度惩罚等性质。
 * <pre>
 * score(q,d) = Σ_t IDF(t) * tf(t,d)*(k1+1) / (tf(t,d) + k1*(1-b+b*|d|/avgdl))
 * IDF(t)    = ln(1 + (N - df + 0.5) / (df + 0.5))   // +1 保证非负
 * </pre>
 */
public final class Bm25Scorer {

    public static final double DEFAULT_K1 = 1.5;
    public static final double DEFAULT_B = 0.75;

    public Bm25Scorer() {
    }

    public double idf(int corpusSize, int docFreq) {
        if (corpusSize <= 0) {
            return 0;
        }
        int safeDf = Math.max(0, Math.min(docFreq, corpusSize));
        return Math.log(1.0 + (corpusSize - safeDf + 0.5) / (safeDf + 0.5));
    }

    public double score(double idf, int termFreq, int docLength, double avgDocLength) {
        return score(idf, termFreq, docLength, avgDocLength, DEFAULT_K1, DEFAULT_B);
    }

    public double score(double idf, int termFreq, int docLength, double avgDocLength, double k1, double b) {
        if (termFreq <= 0 || docLength <= 0 || avgDocLength <= 0) {
            return 0;
        }
        double lengthRatio = (double) docLength / avgDocLength;
        double denominator = termFreq + k1 * (1 - b + b * lengthRatio);
        return idf * (termFreq * (k1 + 1)) / denominator;
    }

    /**
     * 单文档对查询的 BM25 总分。
     *
     * @param queryTerms  查询词（可重复，重复词按 tf 叠加）
     * @param docTermFreq 文档词频表
     * @param docLength   文档长度（与 avgDocLength 同口径）
     * @param idfLookup   term -> IDF，调用方负责 df/N 统计
     */
    public double totalScore(List<String> queryTerms,
                             Map<String, Integer> docTermFreq,
                             int docLength,
                             double avgDocLength,
                             Function<String, Double> idfLookup) {
        if (queryTerms == null || queryTerms.isEmpty() || docTermFreq == null || docTermFreq.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (String term : queryTerms) {
            if (term == null) {
                continue;
            }
            int tf = docTermFreq.getOrDefault(term, 0);
            if (tf <= 0) {
                continue;
            }
            Double idf = idfLookup.apply(term);
            if (idf == null) {
                continue;
            }
            total += score(idf, tf, docLength, avgDocLength);
        }
        return total;
    }
}
