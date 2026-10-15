package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBm25RetrieverTest {

    private final ScoredChunk engineDoc = new ScoredChunk(1L, 1L, 10L, "DAG 引擎",
            "DAG 引擎基于 Kahn 算法做拓扑排序，DFS 检测环路，节点按拓扑序串行执行。", null, null, 0);
    private final ScoredChunk ttsDoc = new ScoredChunk(2L, 1L, 11L, "TTS 节点",
            "TTS 节点把文本合成为语音音频，适合播报配音场景。", null, null, 0);
    private final ScoredChunk reactDoc = new ScoredChunk(3L, 1L, 12L, "ReAct Agent",
            "ReAct Agent 循环交替进行推理与工具调用，trace 记录每轮细节。", null, null, 0);

    private final InMemoryBm25Retriever retriever = new InMemoryBm25Retriever(List.of(engineDoc, ttsDoc, reactDoc));

    @Test
    void shouldRankTermMatchedDocumentFirst() {
        List<ScoredChunk> hits = retriever.retrieve(1L, "DAG 拓扑排序怎么做的", 3);
        // 仅 engineDoc 命中 DAG/拓扑排序 术语；TTS 与 ReAct 语料零命中被过滤（score>0 才入结果）
        assertEquals(1, hits.size());
        assertEquals(1L, hits.getFirst().chunkId(), "术语匹配文档应排首位");
        assertTrue(hits.getFirst().score() > 0);
    }

    @Test
    void shouldPreferRarerTermDocument() {
        // 「检测环路」只出现在 engineDoc（DFS 检测环路）；语音/TTS、trace 语料均不含
        List<ScoredChunk> hits = retriever.retrieve(1L, "检测环路", 3);
        assertEquals(1L, hits.getFirst().chunkId());
        assertEquals(1, hits.size(), "未命中任何查询词的文档应被过滤（score>0 才入结果）");
    }

    @Test
    void shouldReturnEmptyForBlankQueryOrStopWordsOnly() {
        assertTrue(retriever.retrieve(1L, "   ", 5).isEmpty());
        assertTrue(retriever.retrieve(1L, "应该 怎么 什么", 5).isEmpty(), "纯停用词查询应无结果");
        assertTrue(retriever.retrieve(1L, "DAG 引擎", 0).isEmpty());
    }

    @Test
    void shouldHandleEmptyCorpus() {
        InMemoryBm25Retriever empty = new InMemoryBm25Retriever(List.of());
        assertTrue(empty.retrieve(1L, "DAG", 5).isEmpty());
    }
}
