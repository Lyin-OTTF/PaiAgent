package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代3 评测集一次性生成测试：运行后落盘 corpus.json 与 evaluation-set.json。
 * <p>产物已提交到资源目录；日常运行的只读测试（{@link EvaluationSetTest}、
 * {@link RagEndToEndEvaluationTest}）在资源缺失时会经 {@link EvaluationSetGenerator#ensureGenerated()} 自动生成。
 * 修改 {@link CorpusFixture} 语料后重跑本测试重新落盘。
 */
class EvaluationSetGeneratorTest {

    @Test
    void generatesCorpusAndEvaluationSet() throws Exception {
        int caseCount = EvaluationSetGenerator.generate();

        // 规约：评测用例 >=130 条（84 篇语料 × 3 查询 = 252 条）
        assertTrue(caseCount >= 130, "评测用例应不少于 130 条，实际 " + caseCount);
    }
}
