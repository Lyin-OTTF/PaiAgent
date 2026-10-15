package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25ScorerTest {

    private final Bm25Scorer scorer = new Bm25Scorer();

    @Test
    void idfShouldDecreaseWithDocumentFrequencyAndStayNonNegative() {
        double rare = scorer.idf(1000, 1);
        double common = scorer.idf(1000, 900);
        assertTrue(rare > common, "稀有词 IDF 应高于常见词");
        assertTrue(common >= 0, "IDF 不应为负");
        assertEquals(0, scorer.idf(0, 5), "空语料 IDF 为 0");
    }

    @Test
    void scoreShouldSaturateWithTermFrequency() {
        double low = scorer.score(2.0, 1, 100, 100);
        double mid = scorer.score(2.0, 5, 100, 100);
        double high = scorer.score(2.0, 50, 100, 100);
        assertTrue(mid > low * 1.8, "tf 增加应显著提升分数（饱和前）");
        assertTrue(high < mid * 10, "tf 饱和：分数增长应远低于线性");
        assertTrue(high / mid < 3, "tf 从 5 到 50 倍增至多约 3 倍");
    }

    @Test
    void scoreShouldPenalizeLongerDocuments() {
        double shortDoc = scorer.score(2.0, 3, 100, 100);
        double longDoc = scorer.score(2.0, 3, 400, 100);
        assertTrue(longDoc < shortDoc, "同词频下长文档应被惩罚");
    }

    @Test
    void scoreShouldBeZeroForMissingTerm() {
        assertEquals(0, scorer.score(2.0, 0, 100, 100));
        assertEquals(0, scorer.score(2.0, 3, 0, 100));
        assertEquals(0, scorer.score(2.0, 3, 100, 0));
    }

    @Test
    void totalScoreShouldSumQueryTerms() {
        Map<String, Integer> docTf = new LinkedHashMap<>();
        docTf.put("engine", 3);
        docTf.put("workflow", 1);
        Map<String, Double> idf = new LinkedHashMap<>();
        idf.put("engine", 2.0);
        idf.put("workflow", 1.5);

        double total = scorer.totalScore(List.of("engine", "workflow"), docTf, 100, 100, idf::get);

        double expected = scorer.score(2.0, 3, 100, 100) + scorer.score(1.5, 1, 100, 100);
        assertEquals(expected, total, 1e-9);
        // 缺失词不贡献
        assertEquals(0, scorer.totalScore(List.of("absent"), docTf, 100, 100, idf::get));
    }
}
