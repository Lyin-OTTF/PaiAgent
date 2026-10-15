package com.paiagent.engine.rag;

import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代3 上下文接线测试：验证混合检索 Bean 装配、FULLTEXT(ngram) 索引迁移、
 * 以及真实数据库下的 BM25 召回路可用性。
 * <p>注意：涉及 FULLTEXT 检索的用例不能用 @Transactional 包裹——InnoDB FULLTEXT 索引
 * 对同一事务内未提交的插入不可见（FTS 索引仅在提交时更新），故测试数据用 @AfterEach 手动清理。
 * <p>与迭代1 同一纪律：上下文测试只验证装配与集成点，业务行为由纯 Mockito 单测覆盖。
 */
@SpringBootTest(properties = {
        "paiagent.auth.jwt-secret=test-jwt-secret-key-for-spring-context-123456",
        "minio.endpoint=http://localhost:9000",
        "minio.accessKey=minioadmin",
        "minio.secretKey=minioadmin",
        "minio.bucketName=paiagent",
        "minio.publicUrl=http://localhost:9000",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.datasource.password=${MYSQL_PASSWORD:1234}"
})
class HybridRetrievalWiringTest {

    private static final long BASE_ID = 999_888L;

    @Autowired
    private HybridRetriever hybridRetriever;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private KnowledgeChunkMapper chunkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldWireRagBeansWithDefaultProperties() {
        assertTrue(ragProperties.isHybridEnabled(), "默认应开启混合检索");
        assertEquals(60, ragProperties.rrfK());
        assertEquals(10, ragProperties.candidateFactor());
    }

    @Test
    void shouldHaveFulltextIndexOnKnowledgeChunk() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'knowledge_chunk'
                  AND INDEX_NAME = 'ft_knowledge_chunk_content'
                """, Integer.class);
        assertTrue(count != null && count > 0, "knowledge_chunk 应存在 FULLTEXT(ngram) 索引（SchemaMigrationRunner 幂等补建）");
    }

    @Test
    void shouldRetrieveViaBm25FulltextInRealDatabase() {
        hybridRetriever.invalidate(BASE_ID);
        KnowledgeChunk dagChunk = insertChunk(1L, "DAG 引擎", "DAG 引擎基于 Kahn 算法做拓扑排序，DFS 检测环路，节点按拓扑序串行执行。");
        insertChunk(2L, "TTS 节点", "TTS 节点把文本合成为语音音频，适合播报配音场景。");

        // 稠密路无 embedding 配置（测试环境无 API key）应自动降级，词法路经真实 FULLTEXT 召回。
        // 注意：不能加 @Transactional——InnoDB FULLTEXT 索引对同一事务内未提交的插入不可见
        // （FTS 索引仅在提交时更新），故改为手动清理，见 cleanUp()。
        List<ScoredChunk> hits = hybridRetriever.retrieve(BASE_ID, "DAG 拓扑排序 环路检测", 5, 0);

        assertFalse(hits.isEmpty(), "真实 FULLTEXT 索引下应召回到候选");
        assertEquals(dagChunk.getId(), hits.getFirst().chunkId(), "DAG 分片应排首位");
        assertTrue(hits.getFirst().score() > 0, "重排分应为正");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE knowledge_base_id = ?", BASE_ID);
    }

    private KnowledgeChunk insertChunk(long index, String title, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeBaseId(BASE_ID);
        chunk.setDocumentId(BASE_ID + index);
        chunk.setChunkIndex((int) index);
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setStatus("READY");
        chunk.setCharCount(content.length());
        chunkMapper.insert(chunk);
        return chunk;
    }
}
