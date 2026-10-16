package com.paiagent.engine.rag;

import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DenseRetriever 分桶失效单元测试（迭代9 P1-9）。
 *
 * <p>修复前索引与元数据分属两个 Map（{@code indexes} / {@code metadataByBucket}），
 * 且失效时元数据侧用的前缀是 {@code baseId + ":"} 去匹配 {@code baseId:dimN}——
 * 对「按库失效」恰好可用，但对「按库前缀」误伤相邻库（如 baseId=1 会连带清掉 baseId=10）。
 * 重写后合并为单一 {@link Map} 存储，失效即整体丢弃，本用例锁定其前缀边界。
 */
class DenseRetrieverTest {

    private KnowledgeChunkMapper chunkMapper;
    private EmbeddingFunction embeddingFunction;
    private DenseRetriever retriever;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KnowledgeChunkMapper.class);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        embeddingFunction = mock(EmbeddingFunction.class);
        when(embeddingFunction.embed(any(), any())).thenReturn(List.of(0.1, 0.2, 0.3));
        retriever = new DenseRetriever(chunkMapper, embeddingFunction, new RagProperties());
    }

    @Test
    void retrieveShouldBuildOneBucketPerBaseAndDimension() {
        retriever.retrieve(1L, "查询", 3);
        retriever.retrieve(1L, "查询", 3); // 同库同维：命中已有桶，不重复构建
        retriever.retrieve(10L, "查询", 3);

        assertEquals(Set.of("1:dim3", "10:dim3"), new HashSet<>(bucketKeys()));
        verify(chunkMapper, times(2)).selectList(any());
    }

    @Test
    void invalidateShouldOnlyDropTargetBaseBuckets() {
        retriever.retrieve(1L, "查询", 3);
        retriever.retrieve(10L, "查询", 3);

        retriever.invalidate(1L);

        // 前缀必须带分隔符：否则 "1" 会误伤 "10:dim3"
        assertEquals(Set.of("10:dim3"), new HashSet<>(bucketKeys()), "只应失效目标知识库的桶");
    }

    @Test
    void invalidateShouldDropAllDimensionsOfTargetBase() {
        when(embeddingFunction.embed(any(), any()))
                .thenReturn(List.of(0.1, 0.2, 0.3), List.of(0.1, 0.2, 0.3, 0.4));
        retriever.retrieve(2L, "查询", 3);
        retriever.retrieve(2L, "查询", 3);

        assertEquals(Set.of("2:dim3", "2:dim4"), new HashSet<>(bucketKeys()));

        retriever.invalidate(2L);

        assertTrue(bucketKeys().isEmpty(), "同库不同维度（换模型残留）的桶应一并失效");
    }

    /** 索引与元数据同桶：向量命中即可取到内容（修复前两 Map 拆分会出现「命中向量却拿不到内容」）。 */
    @Test
    void retrieveShouldReturnChunkWhenIndexAndMetadataAgree() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(11L);
        chunk.setKnowledgeBaseId(1L);
        chunk.setDocumentId(100L);
        chunk.setTitle("标题");
        chunk.setContent("正文内容");
        chunk.setEmbedding("[0.1,0.2,0.3]");
        chunk.setStatus("READY");
        chunk.setDeleted(0);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

        List<ScoredChunk> results = retriever.retrieve(1L, "查询", 5);

        assertEquals(1, results.size());
        assertEquals(11L, results.get(0).chunkId());
        assertEquals("正文内容", results.get(0).content());
    }

    @SuppressWarnings("unchecked")
    private Set<String> bucketKeys() {
        Map<String, ?> buckets = (Map<String, ?>) ReflectionTestUtils.getField(retriever, "buckets");
        return new HashSet<>(buckets.keySet());
    }
}
