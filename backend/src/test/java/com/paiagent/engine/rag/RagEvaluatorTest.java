package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluatorTest {

    private final RagEvaluator evaluator = new RagEvaluator();

    private List<ScoredChunk> hits(Long... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> new ScoredChunk(id, 1L, 1L, "t", "c", null, null, 1.0))
                .toList();
    }

    @Test
    void shouldComputeRecallAtKAndMrrForKnownRanking() {
        // 相关 {1,2}：case1 top2 全中（recall=1, rank1）；case2 第 3 位才中（recall@2=0.5, rank3）
        Map<String, List<ScoredChunk>> results = Map.of(
                "q1", hits(1L, 2L, 9L),
                "q2", hits(8L, 9L, 1L, 2L)
        );
        List<EvaluationCase> cases = List.of(
                new EvaluationCase("q1", List.of(1L, 2L), "t", null),
                new EvaluationCase("q2", List.of(1L, 2L), "t", null)
        );

        RagEvaluator.Report report = evaluator.evaluate(cases, (baseId, query) -> results.get(query), 2, 3);

        assertEquals(2, report.caseCount());
        // case1 top2={1,2} 全中 recall=1.0；case2 top2={8,9} 全不中 recall=0 -> 均值 0.5
        assertEquals(0.5, report.recallAtK().get(2), 1e-9, "Recall@2 均值应为 0.5");
        // case3 视角：case2 top3={8,9,1} 命中 1/2 -> (1.0+0.5)/2 = 0.75
        assertEquals(0.75, report.recallAtK().get(3), 1e-9);
        assertEquals((1.0 + 1.0 / 3) / 2, report.mrr(), 1e-9);
        assertEquals(2, report.hitRate());
        assertTrue(report.meanLatencyMs() >= 0);
    }

    @Test
    void shouldScoreZeroForNoHits() {
        List<EvaluationCase> cases = List.of(new EvaluationCase("q", List.of(7L), "t", null));
        RagEvaluator.Report report = evaluator.evaluate(cases, (baseId, query) -> hits(1L, 2L), 5);
        assertEquals(0, report.recallAtK().get(5), 1e-9);
        assertEquals(0, report.mrr(), 1e-9);
        assertEquals(0, report.hitRate());
    }

    @Test
    void shouldHandleNullSearchResult() {
        List<EvaluationCase> cases = List.of(new EvaluationCase("q", List.of(1L), "t", null));
        RagEvaluator.Report report = evaluator.evaluate(cases, (baseId, query) -> null, 5);
        assertEquals(1, report.caseCount(), "有用例但检索返回 null 时仍应计数");
        assertEquals(0, report.mrr(), 1e-9);
    }

    @Test
    void shouldHandleEmptyInput() {
        RagEvaluator.Report report = evaluator.evaluate(List.of(), (baseId, query) -> hits(1L), 5);
        assertEquals(0, report.caseCount());
        assertEquals(0, report.mrr(), 1e-9);
    }

    @Test
    void shouldSkipCasesWithEmptyRelevantSet() {
        List<EvaluationCase> cases = List.of(
                new EvaluationCase("q1", List.of(), "t", null),
                new EvaluationCase("q2", List.of(1L), "t", null)
        );
        RagEvaluator.Report report = evaluator.evaluate(cases, (baseId, query) -> hits(1L), 5);
        assertEquals(1, report.caseCount(), "无相关集的用例应被跳过");
        assertEquals(1.0, report.recallAtK().get(5), 1e-9);
    }
}
