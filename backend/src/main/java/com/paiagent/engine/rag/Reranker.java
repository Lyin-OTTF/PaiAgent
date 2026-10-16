package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 融合后重排：把 RRF 融合分与查询-文档的词汇证据合成 0-1 的可比分数。
 * <pre>
 * final = wRrf * rrfNorm + wCoverage * coverage + wTitle * titleHit
 * rrfNorm  融合分批内归一化（max 归 1）
 * coverage 查询词在正文的加权覆盖率（0-1）
 * titleHit 查询词在标题的覆盖率（0-1）
 * </pre>
 * 输出分数与历史 cosine 阈值（默认 0.2）同量级，检索入口的 scoreThreshold 语义不变。
 */
public class Reranker {

    private static final double DEFAULT_RRF_WEIGHT = 0.4;
    private static final double DEFAULT_COVERAGE_WEIGHT = 0.4;
    private static final double DEFAULT_TITLE_WEIGHT = 0.2;

    private final ChineseAnalyzer analyzer;
    private final double rrfWeight;
    private final double coverageWeight;
    private final double titleWeight;

    public Reranker() {
        this(ChineseAnalyzer.shared(), DEFAULT_RRF_WEIGHT, DEFAULT_COVERAGE_WEIGHT, DEFAULT_TITLE_WEIGHT);
    }

    public Reranker(ChineseAnalyzer analyzer, double rrfWeight, double coverageWeight, double titleWeight) {
        this.analyzer = analyzer;
        // 迭代7（报告低危项）：权重归一化——配置之和不为 1 时，加权和会被 clamp01 截断
        // （或整体偏小），使最终分与「scoreThreshold 默认 0.2」的既定语义偏离。
        double[] normalized = normalizeWeights(rrfWeight, coverageWeight, titleWeight);
        this.rrfWeight = normalized[0];
        this.coverageWeight = normalized[1];
        this.titleWeight = normalized[2];
    }

    /**
     * 归一化三元权重：负数按 0 处理；三者全为 0（或非法）时回落默认权重。
     * 归一化后三者之和恒为 1，加权和天然落在 [0,1]，clamp01 只做兜底。
     */
    private static double[] normalizeWeights(double rrf, double coverage, double title) {
        double r = Math.max(0, rrf);
        double c = Math.max(0, coverage);
        double t = Math.max(0, title);
        double sum = r + c + t;
        if (sum <= 0) {
            return new double[]{DEFAULT_RRF_WEIGHT, DEFAULT_COVERAGE_WEIGHT, DEFAULT_TITLE_WEIGHT};
        }
        return new double[]{r / sum, c / sum, t / sum};
    }

    /**
     * @param query     原始查询（未归一化）
     * @param candidates 融合后的候选，其 score 字段被本方法覆盖为重排分
     * @param rrfScores chunkId -> RRF 融合分；缺失的候选按 0 计
     * @return 按重排分降序的候选列表
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, Map<Long, Double> rrfScores) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = analyzer.terms(query);
        double maxRrf = 0;
        for (Map.Entry<Long, Double> entry : safe(rrfScores).entrySet()) {
            maxRrf = Math.max(maxRrf, Math.max(0, entry.getValue()));
        }

        List<ScoredChunk> ranked = new java.util.ArrayList<>(candidates.size());
        for (ScoredChunk candidate : candidates) {
            double rrf = Math.max(0, safe(rrfScores).getOrDefault(candidate.chunkId(), 0.0));
            double rrfNorm = maxRrf > 0 ? rrf / maxRrf : 0;
            double coverage = queryTerms.isEmpty() ? 0 : weightedCoverage(queryTerms, candidate.content());
            double titleHit = queryTerms.isEmpty() ? 0 : weightedCoverage(queryTerms, candidate.title());
            double finalScore = clamp01(
                    rrfWeight * rrfNorm + coverageWeight * coverage + titleWeight * titleHit);
            ranked.add(candidate.withScore(finalScore));
        }
        ranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return ranked;
    }

    /**
     * 查询词加权覆盖率：长词权重高（与分词权重规则一致：min(4, max(1, len-1))）。
     */
    public double weightedCoverage(Set<String> queryTerms, String text) {
        if (queryTerms == null || queryTerms.isEmpty() || text == null || text.isBlank()) {
            return 0;
        }
        String normalized = analyzer.normalize(text);
        double totalWeight = 0;
        double matchedWeight = 0;
        for (String term : queryTerms) {
            double weight = Math.min(4, Math.max(1, term.length() - 1));
            totalWeight += weight;
            if (normalized.contains(term)) {
                matchedWeight += weight;
            }
        }
        if (totalWeight == 0 || matchedWeight == 0) {
            return 0;
        }
        return Math.min(1.0, matchedWeight / totalWeight);
    }

    private Map<Long, Double> safe(Map<Long, Double> rrfScores) {
        return rrfScores == null ? Map.of() : rrfScores;
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /**
     * 候选去重合并：同 chunkId 保留先出现者（调用方按 BM25 -> dense 顺序喂入，即保留词汇路元数据）。
     */
    public static Map<Long, ScoredChunk> mergeCandidates(List<List<ScoredChunk>> routes) {
        Map<Long, ScoredChunk> merged = new LinkedHashMap<>();
        for (List<ScoredChunk> route : routes) {
            if (route == null) {
                continue;
            }
            for (ScoredChunk chunk : route) {
                merged.putIfAbsent(chunk.chunkId(), chunk);
            }
        }
        return merged;
    }
}
