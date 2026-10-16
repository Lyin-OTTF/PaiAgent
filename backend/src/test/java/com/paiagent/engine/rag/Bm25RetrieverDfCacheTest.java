package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bm25Retriever df 缓存单元测试（迭代9 P1-9）。
 *
 * <p>修复前 {@code dfCache} 是无界 ConcurrentHashMap：键为「知识库 × 词条」，
 * 随语料与查询用语无界累积。现加入 {@code MAX_DF_CACHE_ENTRIES} 上限，
 * 达上限后只读不写（已有条目仍命中），写入侧 {@code invalidateStats} 失效后可继续填充。
 */
class Bm25RetrieverDfCacheTest {

    private KnowledgeChunkMapper chunkMapper;
    private Bm25Retriever retriever;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KnowledgeChunkMapper.class);
        retriever = new Bm25Retriever(chunkMapper);
        when(chunkMapper.selectCorpusStats(any()))
                .thenReturn(Map.of("corpusSize", 10, "avgCharLength", 6.0));
        when(chunkMapper.countByFulltextTerm(any(), any())).thenReturn(1);
    }

    /** 文档正文包含全部查询词，确保每个查询词的 df 都会被查询并缓存。 */
    private void stubCandidateWithContent(String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(1L);
        chunk.setKnowledgeBaseId(1L);
        chunk.setContent(content);
        when(chunkMapper.fulltextCandidates(any(), any(), anyInt())).thenReturn(List.of(chunk));
    }

    @Test
    void idfShouldCacheTermsAndSkipDatabaseOnSecondRetrieve() {
        String query = "测试词条";
        stubCandidateWithContent(query);
        int termCount = ChineseAnalyzer.shared().terms(query).size();
        assertTrue(termCount > 0, "查询应被切出词条");

        retriever.retrieve(1L, query, 5);
        retriever.retrieve(1L, query, 5);

        // 第二次检索全部命中 df 缓存，不再回查数据库
        verify(chunkMapper, times(termCount)).countByFulltextTerm(any(), any());
    }

    @Test
    void idfCacheShouldStopGrowingAtCapacity() {
        stubCandidateWithContent("测试词条");
        int max = (int) ReflectionTestUtils.getField(Bm25Retriever.class, "MAX_DF_CACHE_ENTRIES");
        Map<String, Integer> cache = dfCache();
        for (int i = 0; i < max; i++) {
            cache.put("1:seed" + i, 1);
        }
        assertEquals(max, cache.size());

        retriever.retrieve(1L, "测试词条", 5);

        // 达上限后只读不写：不得再新增条目（无界增长回归）
        assertEquals(max, cache.size(), "df 缓存达上限后不得继续新增");
    }

    @Test
    void invalidateStatsShouldFreeRoomForNewEntries() {
        stubCandidateWithContent("测试词条");
        int max = (int) ReflectionTestUtils.getField(Bm25Retriever.class, "MAX_DF_CACHE_ENTRIES");
        Map<String, Integer> cache = dfCache();
        for (int i = 0; i < max; i++) {
            cache.put("1:seed" + i, 1);
        }

        retriever.invalidateStats(1L);
        assertTrue(cache.isEmpty(), "写入侧失效应清空该库的词条缓存");

        retriever.retrieve(1L, "测试词条", 5);

        assertTrue(cache.size() > 0, "失效后应可重新填充缓存");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> dfCache() {
        return (Map<String, Integer>) ReflectionTestUtils.getField(retriever, "dfCache");
    }
}
