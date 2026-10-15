package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationSetTest {

    @Test
    void shouldLoadBuiltInEvaluationSet() {
        EvaluationSetGenerator.ensureGenerated();
        EvaluationSet set = EvaluationSet.loadFromClasspath();

        assertTrue(set.cases().size() >= 130, "内置评测集应不少于 130 条，实际 " + set.cases().size());
        assertTrue(set.cases().stream().allMatch(c -> c.query() != null && !c.query().isBlank()));
        assertTrue(set.cases().stream().allMatch(c -> c.relevantChunkIds() != null && !c.relevantChunkIds().isEmpty()));
        // 主题覆盖：11 个领域主题均有用例
        long topics = set.cases().stream().map(EvaluationCase::topic).distinct().count();
        assertTrue(topics >= 10, "主题应覆盖 10+ 个，实际 " + topics);
    }

    @Test
    void shouldNotContainDuplicateQueryRelevantPairs() {
        EvaluationSetGenerator.ensureGenerated();
        EvaluationSet set = EvaluationSet.loadFromClasspath();
        long distinct = set.cases().stream()
                .map(c -> c.query() + "->" + c.relevantChunkIds())
                .distinct()
                .count();
        assertEquals(set.cases().size(), distinct, "查询到相关集映射应唯一");
        assertFalse(set.cases().isEmpty());
    }
}
