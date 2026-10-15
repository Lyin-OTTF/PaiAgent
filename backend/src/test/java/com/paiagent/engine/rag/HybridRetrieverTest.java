package com.paiagent.engine.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {

    private Bm25Retriever bm25Retriever;
    private DenseRetriever denseRetriever;
    private HybridRetriever hybridRetriever;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        bm25Retriever = mock(Bm25Retriever.class);
        denseRetriever = mock(DenseRetriever.class);
        properties = new RagProperties();
        properties.setCandidateFactor(2);
        hybridRetriever = new HybridRetriever(bm25Retriever, denseRetriever, new Reranker(), properties);
    }

    private ScoredChunk chunk(Long id, String title, String content, double score) {
        return new ScoredChunk(id, 1L, 10L, title, content, null, null, score);
    }

    @Test
    void shouldFuseBothRoutesAndRankMultiRouteHitFirst() {
        // c2 两路都命中（BM25 第 2、dense 第 1），RRF 应最高
        when(bm25Retriever.retrieve(eq(1L), eq("DAG 调度"), anyInt()))
                .thenReturn(List.of(chunk(1L, "DAG 引擎", "拓扑排序与环检测", 5.0), chunk(2L, "DAG 调度", "有界并行调度器", 4.0)));
        when(denseRetriever.retrieve(eq(1L), eq("DAG 调度"), anyInt()))
                .thenReturn(List.of(chunk(2L, "DAG 调度", "有界并行调度器", 0.9), chunk(3L, "TTS", "语音合成", 0.8)));

        List<ScoredChunk> hits = hybridRetriever.retrieve(1L, "DAG 调度", 3, 0);

        assertEquals(3, hits.size());
        assertEquals(2L, hits.getFirst().chunkId(), "两路均命中的候选应排首位");
        assertTrue(hits.getFirst().score() >= hits.get(1).score());
    }

    @Test
    void shouldApplyScoreThresholdAndTopK() {
        when(bm25Retriever.retrieve(eq(1L), any(), anyInt()))
                .thenReturn(List.of(chunk(1L, "DAG 调度", "有界并行 DAG 调度器执行同层节点", 5.0)));
        when(denseRetriever.retrieve(eq(1L), any(), anyInt())).thenReturn(List.of());

        List<ScoredChunk> hits = hybridRetriever.retrieve(1L, "DAG 调度", 2, 0.5);

        assertEquals(1, hits.size(), "达到阈值的候选应保留");
        List<ScoredChunk> filtered = hybridRetriever.retrieve(1L, "完全无关的查询词", 2, 0.5);
        assertTrue(filtered.isEmpty(), "低于阈值的候选应被过滤");
    }

    @Test
    void shouldKeepLexicalRouteWhenDenseRouteThrows() {
        when(bm25Retriever.retrieve(eq(1L), any(), anyInt()))
                .thenReturn(List.of(chunk(1L, "BM25", "词法检索召回路", 5.0)));
        when(denseRetriever.retrieve(eq(1L), any(), anyInt()))
                .thenThrow(new IllegalStateException("vector model unavailable"));

        List<ScoredChunk> hits = hybridRetriever.retrieve(1L, "词法检索", 5, 0);

        assertEquals(1, hits.size(), "稠密路熔断后词法路应仍可服务");
        assertEquals(1L, hits.getFirst().chunkId());
    }

    @Test
    void shouldReturnEmptyWhenBothRoutesEmpty() {
        when(bm25Retriever.retrieve(eq(1L), any(), anyInt())).thenReturn(List.of());
        when(denseRetriever.retrieve(eq(1L), any(), anyInt())).thenReturn(List.of());
        assertTrue(hybridRetriever.retrieve(1L, "任意查询", 5, 0).isEmpty());
    }

    @Test
    void shouldInvalidateBothRoutes() {
        hybridRetriever.invalidate(1L);
        verify(bm25Retriever).invalidateStats(1L);
        verify(denseRetriever).invalidate(1L);
    }

    @Test
    void shouldRejectInvalidArgumentsWithoutTouchingRoutes() {
        assertTrue(hybridRetriever.retrieve(null, "query", 5, 0).isEmpty());
        assertTrue(hybridRetriever.retrieve(1L, "  ", 5, 0).isEmpty());
        assertTrue(hybridRetriever.retrieve(1L, "query", 0, 0).isEmpty());
        verify(bm25Retriever, never()).retrieve(any(), any(), anyInt());
    }

    @Test
    void shouldPassCandidateFactorToRoutes() {
        properties.setCandidateFactor(4);
        when(bm25Retriever.retrieve(eq(1L), eq("q"), eq(12))).thenReturn(List.of());
        when(denseRetriever.retrieve(eq(1L), eq("q"), eq(12))).thenReturn(List.of());
        hybridRetriever.retrieve(1L, "q", 3, 0);
        verify(bm25Retriever).retrieve(1L, "q", 12);
        verify(denseRetriever).retrieve(1L, "q", 12);
    }
}
