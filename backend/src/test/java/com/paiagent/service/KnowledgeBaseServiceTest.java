package com.paiagent.service;

import com.paiagent.engine.rag.HybridRetriever;
import com.paiagent.engine.rag.RagProperties;
import com.paiagent.engine.rag.ScoredChunk;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeIndexTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @Test
    void shouldScoreChineseTextMatchesWhenEmbeddingIsUnavailable() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeBaseService service = createService(chunkMapper);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(1L, "联网搜索能力", "Agent Plan 支持联网搜索、网页读取和知识库检索，可以获取实时权威信息。"),
                chunk(2L, "语音能力", "TTS 节点负责把文本转换成语音，适合播报和配音。")
        ));

        Map<String, Object> result = service.retrieve("1", "联网搜索应该怎么做?", List.of(), 5, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertEquals(1, chunks.size());
        assertEquals(1L, chunks.getFirst().get("chunkId"));
        assertTrue((double) chunks.getFirst().get("score") > 0);
    }

    @Test
    void shouldNotReturnZeroScoreChunksWhenThresholdAllowsFallbackSearch() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeBaseService service = createService(chunkMapper);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(1L, "语音能力", "TTS 节点负责把文本转换成语音，适合播报和配音。")
        ));

        Map<String, Object> result = service.retrieve("1", "联网搜索应该怎么做?", List.of(), 5, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertTrue(chunks.isEmpty());
        assertFalse(String.valueOf(result.get("context")).contains("TTS"));
    }

    @Test
    void shouldRouteSearchRuntimeThroughHybridRetrieverWhenEnabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
        KnowledgeBaseService service = createService(chunkMapper, hybridRetriever, new RagProperties());
        when(hybridRetriever.retrieve(eq(1L), eq("联网搜索怎么做"), eq(3), eq(0.2)))
                .thenReturn(List.of(new ScoredChunk(7L, 1L, 10L, "t", "联网搜索内容", null, null, 0.9)));

        Map<String, Object> result = service.searchRuntime("1", "联网搜索怎么做", 3, 0.2);

        assertEquals("hybrid", result.get("mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertEquals(1, chunks.size());
        assertEquals(7L, chunks.getFirst().get("chunkId"));
        verify(hybridRetriever).retrieve(1L, "联网搜索怎么做", 3, 0.2);
    }

    @Test
    void shouldFallbackToLegacyScanWhenHybridRetrieverThrows() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
        KnowledgeBaseService service = createService(chunkMapper, hybridRetriever, new RagProperties());
        when(hybridRetriever.retrieve(eq(1L), any(), any(Integer.class), any(Double.class)))
                .thenThrow(new IllegalStateException("fulltext index missing"));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(1L, "联网搜索能力", "Agent Plan 支持联网搜索、网页读取和知识库检索，可以获取实时权威信息。")
        ));

        Map<String, Object> result = service.searchRuntime("1", "联网搜索应该怎么做?", 5, 0);

        assertEquals("legacy", result.get("mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertEquals(1, chunks.size());
        assertEquals(1L, chunks.getFirst().get("chunkId"));
    }

    @Test
    void shouldSkipHybridWhenDisabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
        RagProperties properties = new RagProperties();
        properties.setHybridEnabled(false);
        KnowledgeBaseService service = createService(chunkMapper, hybridRetriever, properties);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(1L, "联网搜索能力", "Agent Plan 支持联网搜索、网页读取和知识库检索，可以获取实时权威信息。")
        ));

        Map<String, Object> result = service.searchRuntime("1", "联网搜索应该怎么做?", 5, 0);

        assertEquals("legacy", result.get("mode"));
        verify(hybridRetriever, org.mockito.Mockito.never()).retrieve(any(), any(), any(Integer.class), any(Double.class));
    }

    private KnowledgeBaseService createService(KnowledgeChunkMapper chunkMapper) {
        return createService(chunkMapper, mock(HybridRetriever.class), new RagProperties());
    }

    private KnowledgeBaseService createService(KnowledgeChunkMapper chunkMapper, HybridRetriever hybridRetriever, RagProperties properties) {
        KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase base = new KnowledgeBase();
        base.setId(1L);
        base.setName("测试知识库");
        when(baseMapper.selectById(1L)).thenReturn(base);
        return new KnowledgeBaseService(
                baseMapper,
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeIndexTaskMapper.class),
                mock(AgentPlanConfigResolver.class),
                mock(VolcengineAgentPlanClient.class),
                hybridRetriever,
                properties
        );
    }

    private KnowledgeChunk chunk(Long id, String title, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(1L);
        chunk.setDocumentId(10L + id);
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setEmbedding("[]");
        chunk.setStatus("READY");
        return chunk;
    }
}
