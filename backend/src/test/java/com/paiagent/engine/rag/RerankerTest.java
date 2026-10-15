package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankerTest {

    private final Reranker reranker = new Reranker();

    private ScoredChunk chunk(Long id, String title, String content) {
        return new ScoredChunk(id, 1L, 10L, title, content, null, null, 0);
    }

    @Test
    void shouldRankByCoverageWhenRrfEqual() {
        ScoredChunk strong = chunk(1L, "DAG 调度", "有界并行 DAG 调度器负责拓扑分层与并发执行，失败传播取消同层任务。");
        ScoredChunk weak = chunk(2L, "TTS", "语音合成节点把文本转换为音频。");
        Map<Long, Double> rrf = Map.of(1L, 0.02, 2L, 0.02);

        List<ScoredChunk> ranked = reranker.rerank("DAG 并行调度", List.of(weak, strong), rrf);

        assertEquals(1L, ranked.getFirst().chunkId(), "正文覆盖查询词更多者应排前");
        assertTrue(ranked.getFirst().score() > 0);
        assertTrue(ranked.getFirst().score() <= 1, "重排分应归一化到 0-1");
    }

    @Test
    void shouldProduceScoresInZeroToOneRange() {
        List<ScoredChunk> candidates = List.of(
                chunk(1L, "工具网关", "ToolGateway 执行前做自治等级校验与幂等守卫。"),
                chunk(2L, "审批流", "ApprovalRecord 保存人工审批记录。")
        );
        Map<Long, Double> rrf = new LinkedHashMap<>();
        rrf.put(1L, 0.03);
        rrf.put(2L, 0.01);

        List<ScoredChunk> ranked = reranker.rerank("工具网关 自治等级", candidates, rrf);

        for (ScoredChunk c : ranked) {
            assertTrue(c.score() >= 0 && c.score() <= 1);
        }
        assertTrue(ranked.get(0).score() >= ranked.get(1).score(), "应按重排分降序");
    }

    @Test
    void shouldWeightTitleHit() {
        // 两者正文覆盖率均为 1.0（都含 hnsw 与 索引参数），标题命中成为决定性因素
        ScoredChunk titled = chunk(1L, "HNSW 索引参数", "HNSW 索引参数控制图结构与搜索宽度。");
        ScoredChunk untitled = chunk(2L, "图结构说明", "HNSW 索引参数控制图结构与搜索宽度。");
        Map<Long, Double> rrf = Map.of(1L, 0.02, 2L, 0.02);

        List<ScoredChunk> ranked = reranker.rerank("HNSW 索引参数", List.of(untitled, titled), rrf);

        assertEquals(1L, ranked.getFirst().chunkId(), "标题命中应获得加分");
        assertTrue(ranked.getFirst().score() > ranked.get(1).score());
    }

    @Test
    void shouldComputeWeightedCoverage() {
        double coverage = reranker.weightedCoverage(Set.of("检索", "引擎"), "检索引擎负责向量召回");
        assertTrue(coverage > 0.9, "两词均命中时覆盖率应接近 1，实际 " + coverage);
        assertEquals(0, reranker.weightedCoverage(Set.of("索引"), ""));
        assertEquals(0, reranker.weightedCoverage(Set.of(), "任意内容"));
    }

    @Test
    void shouldHandleEmptyCandidates() {
        assertTrue(reranker.rerank("query", List.of(), Map.of()).isEmpty());
        assertTrue(reranker.rerank("query", null, null).isEmpty());
    }

    @Test
    void shouldMergeCandidatesKeepingFirstOccurrence() {
        Map<Long, ScoredChunk> merged = Reranker.mergeCandidates(List.of(
                List.of(chunk(1L, "a", "first")),
                List.of(chunk(1L, "a", "second"), chunk(2L, "b", "other"))
        ));
        assertEquals(2, merged.size());
        assertEquals("first", merged.get(1L).content(), "同 chunkId 应保留先出现者");
    }
}
