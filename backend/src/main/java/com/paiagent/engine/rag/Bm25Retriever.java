package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迭代3 · BM25 检索路（词法路）。
 * <p>两段式：MySQL FULLTEXT(ngram) 召回候选 → 应用层标准 BM25 公式重打分。
 * 直接采信 MySQL MATCH 分数会把 InnoDB 内部排序（IDF 加权、非标准参数）当作最终分，
 * 无法与 RRF/阈值语义对齐；召回用 FULLTEXT 加速，打分用可解释的标准 BM25。
 * <p>语料统计缓存：avgdl/N 带 TTL；df 缓存无 TTL，靠 {@link #invalidateStats(Long)} 在写入时失效。
 * 检索路径每次全量统计会拖慢请求，写入侧主动失效保证一致性。
 */
@Component
public class Bm25Retriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25Retriever.class);

    private static final int MIN_CANDIDATES = 50;
    private static final long STATS_TTL_MILLIS = 5 * 60 * 1000L;

    private final KnowledgeChunkMapper chunkMapper;
    private final ChineseAnalyzer analyzer = ChineseAnalyzer.shared();
    private final Bm25Scorer scorer = new Bm25Scorer();

    private final Map<Long, CorpusStats> statsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> dfCache = new ConcurrentHashMap<>();

    public Bm25Retriever(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    private record CorpusStats(int corpusSize, double avgDocLength, long cachedAt) {
    }

    @Override
    public List<ScoredChunk> retrieve(Long knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null || topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = analyzer.terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        int candidateLimit = Math.max(MIN_CANDIDATES, topK * 10);
        List<KnowledgeChunk> candidates = chunkMapper.fulltextCandidates(
                knowledgeBaseId, toBooleanQuery(terms), candidateLimit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        CorpusStats stats = corpusStats(knowledgeBaseId);
        List<String> queryTerms = new ArrayList<>(terms);

        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (KnowledgeChunk chunk : candidates) {
            Map<String, Integer> docTermFreq = analyzer.termFrequencies(chunk.getContent());
            double score = scorer.totalScore(queryTerms, docTermFreq, analyzer.normalizedLength(chunk.getContent()),
                    stats.avgDocLength(), term -> idf(stats, term, knowledgeBaseId));
            scored.add(new ScoredChunk(chunk.getId(), chunk.getKnowledgeBaseId(), chunk.getDocumentId(),
                    chunk.getTitle(), chunk.getContent(), chunk.getSourceUrl(), chunk.getTags(), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        List<ScoredChunk> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        return List.copyOf(top);
    }

    /**
     * 使指定知识库的统计缓存失效（写入/删除分片后由 HybridRetriever 调用）。
     */
    public void invalidateStats(Long knowledgeBaseId) {
        statsCache.remove(knowledgeBaseId);
        dfCache.keySet().removeIf(key -> key.startsWith(knowledgeBaseId + ":"));
    }

    private double idf(CorpusStats stats, String term, Long knowledgeBaseId) {
        String cacheKey = knowledgeBaseId + ":" + term;
        Integer cached = dfCache.get(cacheKey);
        int df;
        if (cached != null) {
            df = cached;
        } else {
            df = chunkMapper.countByFulltextTerm(knowledgeBaseId, term);
            dfCache.put(cacheKey, df);
        }
        return scorer.idf(stats.corpusSize(), df);
    }

    private CorpusStats corpusStats(Long knowledgeBaseId) {
        CorpusStats cached = statsCache.get(knowledgeBaseId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.cachedAt() < STATS_TTL_MILLIS) {
            return cached;
        }
        Map<String, Object> row = chunkMapper.selectCorpusStats(knowledgeBaseId);
        int corpusSize = row == null ? 0 : ((Number) row.getOrDefault("corpusSize", 0)).intValue();
        double avgCharLength = row == null ? 0 : ((Number) row.getOrDefault("avgCharLength", 0)).doubleValue();
        CorpusStats stats = new CorpusStats(corpusSize, Math.max(1.0, avgCharLength), now);
        statsCache.put(knowledgeBaseId, stats);
        return stats;
    }

    /**
     * BOOLEAN MODE 查询词转义：仅保留中英数字，其余折叠为空格，空格分隔即隐式 OR。
     * BOOLEAN MODE 的 +-@&lt;&gt;()~*" 均为操作符，原样拼接会导致语法错误。
     */
    private String toBooleanQuery(Set<String> terms) {
        List<String> escaped = new ArrayList<>(terms.size());
        for (String term : terms) {
            String safe = term.replaceAll("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
            if (!safe.isEmpty()) {
                escaped.add(safe);
            }
        }
        return String.join(" ", escaped);
    }
}
