package com.paiagent.engine.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 迭代3 · 离线评测器：Recall@K / MRR / 检索延迟分位数。
 * <p>指标口径（行业常用，简历可讲）：
 * <ul>
 *   <li>Recall@K = |relevant ∩ topK| / |relevant|，对全部用例取宏平均</li>
 *   <li>MRR = mean(1 / rank)，rank 为第一个相关结果的排名（1 起），无命中记 0</li>
 * </ul>
 * 检索函数由调用方注入（DB 混合检索或内存混合检索），评测器本身不碰 IO，
 * 因此同一份评测集可分别评测 hybrid / 单路 / 旧实现，指标可直接对比。
 */
public class RagEvaluator {

    /** 默认评测的知识库 ID（迭代7：原先硬编码 1L，现抽为常量并支持显式指定）。 */
    public static final long DEFAULT_KNOWLEDGE_BASE_ID = 1L;

    /**
     * @param searchFn (knowledgeBaseId, query) -> 按相关度降序的候选
     */
    public record Report(
            List<Integer> ks,
            Map<Integer, Double> recallAtK,
            double mrr,
            /** 至少命中一次相关结果的用例数（迭代7：原字段名 hitRate 名不副实——它是计数而非比率）。 */
            int hitCases,
            int caseCount,
            double meanLatencyMs,
            double p50LatencyMs,
            double p99LatencyMs
    ) {
    }

    public Report evaluate(List<EvaluationCase> cases, BiFunction<Long, String, List<ScoredChunk>> searchFn, int... ks) {
        return evaluate(DEFAULT_KNOWLEDGE_BASE_ID, cases, searchFn, ks);
    }

    /**
     * 指定知识库的评测（迭代7 · 报告 #16）。
     *
     * <p>修复前检索调用硬编码 {@code knowledgeBaseId = 1L}：评测其它知识库时会悄悄
     * 拿 1 号库的数据出结论，指标失去意义。现由调用方显式指定。
     *
     * @param knowledgeBaseId 被评测的知识库 ID
     */
    public Report evaluate(long knowledgeBaseId, List<EvaluationCase> cases,
                           BiFunction<Long, String, List<ScoredChunk>> searchFn, int... ks) {
        if (cases == null || cases.isEmpty()) {
            List<Integer> requestedKs = Arrays.stream(ks == null ? new int[0] : ks).boxed().toList();
            return new Report(requestedKs, Map.of(), 0, 0, 0, 0, 0, 0);
        }
        int[] kValues = ks == null || ks.length == 0 ? new int[]{5, 10} : ks.clone();
        Map<Integer, Double> recallSum = new LinkedHashMap<>();
        for (int k : kValues) {
            recallSum.put(k, 0.0);
        }
        double mrrSum = 0;
        int hitCases = 0;
        List<Double> latencies = new ArrayList<>();

        for (EvaluationCase evaluationCase : cases) {
            if (evaluationCase.relevantChunkIds() == null || evaluationCase.relevantChunkIds().isEmpty()) {
                continue;
            }
            long start = System.nanoTime();
            List<ScoredChunk> hits = searchFn.apply(knowledgeBaseId, evaluationCase.query());
            latencies.add((System.nanoTime() - start) / 1_000_000.0);
            List<Long> hitIds = hits == null ? List.of() : hits.stream().map(ScoredChunk::chunkId).toList();
            Set<Long> relevant = Set.copyOf(evaluationCase.relevantChunkIds());

            int firstRelevantRank = 0;
            for (int i = 0; i < hitIds.size(); i++) {
                if (relevant.contains(hitIds.get(i))) {
                    firstRelevantRank = i + 1;
                    break;
                }
            }
            if (firstRelevantRank > 0) {
                hitCases++;
                mrrSum += 1.0 / firstRelevantRank;
            }
            for (int k : kValues) {
                long hitCount = hitIds.stream().limit(k).filter(relevant::contains).count();
                double recall = (double) hitCount / relevant.size();
                recallSum.merge(k, recall, Double::sum);
            }
        }

        int caseCount = (int) cases.stream()
                .filter(c -> c.relevantChunkIds() != null && !c.relevantChunkIds().isEmpty())
                .count();
        Map<Integer, Double> recallAtK = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> entry : recallSum.entrySet()) {
            recallAtK.put(entry.getKey(), caseCount == 0 ? 0 : entry.getValue() / caseCount);
        }
        return new Report(
                Arrays.stream(kValues).boxed().toList(),
                recallAtK,
                caseCount == 0 ? 0 : mrrSum / caseCount,
                hitCases,
                caseCount,
                mean(latencies),
                percentile(latencies, 50),
                percentile(latencies, 99));
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double percentile(List<Double> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
