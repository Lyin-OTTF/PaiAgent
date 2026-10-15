package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 迭代3：BM25 召回路——MySQL 8.0 InnoDB FULLTEXT(ngram parser) 候选召回。
     * <p>项目未注册 MyBatis-Plus 拦截器，逻辑删除条件需在 SQL 中显式声明。
     * MATCH 分数仅用于候选召回排序，最终 BM25 分由应用层 {@code Bm25Scorer} 重算。
     *
     * @param booleanQuery 已转义的布尔查询词（空格分隔，隐式 OR）
     * @param limit        候选条数上限（通常为 topK 的候选倍数）
     */
    @Select("""
            SELECT id, knowledge_base_id AS knowledgeBaseId, document_id AS documentId, chunk_index AS chunkIndex,
                   title, content, source_url AS sourceUrl, tags, embedding_model AS embeddingModel,
                   embedding, status, char_count AS charCount, created_at AS createdAt, deleted
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND status = 'READY'
              AND deleted = 0
              AND MATCH(content) AGAINST (#{booleanQuery} IN BOOLEAN MODE)
            ORDER BY MATCH(content) AGAINST (#{booleanQuery} IN BOOLEAN MODE) DESC
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> fulltextCandidates(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                            @Param("booleanQuery") String booleanQuery,
                                            @Param("limit") int limit);

    /**
     * 迭代3：词项文档频率（df）统计，用于 BM25 的 IDF。按 base 维度统计 READY 未删除分片。
     */
    @Select("""
            SELECT COUNT(*)
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND status = 'READY'
              AND deleted = 0
              AND MATCH(content) AGAINST (#{term} IN BOOLEAN MODE)
            """)
    int countByFulltextTerm(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("term") String term);

    /**
     * 迭代3：语料统计 N（文档数）与 avgdl（平均归一化长度），BM25 长度归一化所需。
     */
    @Select("""
            SELECT COUNT(*) AS corpusSize, COALESCE(AVG(CHAR_LENGTH(content)), 0) AS avgCharLength
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{knowledgeBaseId}
              AND status = 'READY'
              AND deleted = 0
            """)
    Map<String, Object> selectCorpusStats(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
