package com.paiagent.engine.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseAnalyzerTest {

    private final ChineseAnalyzer analyzer = ChineseAnalyzer.shared();

    @Test
    void shouldNormalizeCaseAndPunctuation() {
        assertEquals("workflow engine 2026", analyzer.normalize("Workflow, ENGINE!! 2026？"));
        assertEquals("", analyzer.normalize(null));
    }

    @Test
    void shouldSplitChineseIntoTwoToFourGrams() {
        // 「知识库检索」五字 -> 2/3/4 gram 全覆盖；停用词「怎么」被剔除
        Set<String> terms = analyzer.terms("知识库检索怎么做");
        assertTrue(terms.contains("知识"));
        assertTrue(terms.contains("识库"));
        assertTrue(terms.contains("知识库"));
        assertTrue(terms.contains("检索"));
        assertFalse(terms.contains("怎么"));
        assertFalse(terms.contains("做"));
    }

    @Test
    void shouldKeepShortChineseWordWhole() {
        Set<String> terms = analyzer.terms("审批");
        assertEquals(Set.of("审批"), terms);
    }

    @Test
    void shouldKeepEnglishWordsAndDropStopWords() {
        Set<String> terms = analyzer.terms("how to use the Workflow Engine");
        assertTrue(terms.contains("workflow"));
        assertTrue(terms.contains("engine"));
        assertFalse(terms.contains("the"));
        assertFalse(terms.contains("how"));
    }

    @Test
    void shouldCountTermFrequencies() {
        Map<String, Integer> frequencies = analyzer.termFrequencies("引擎 引擎 调度");
        assertEquals(2, frequencies.get("引擎"));
        assertEquals(1, frequencies.get("调度"));
    }

    @Test
    void shouldReportNormalizedLength() {
        assertEquals("workflow engine".length(), analyzer.normalizedLength("Workflow Engine!"));
    }
}
