package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.engine.memory.MemoryScope;
import com.paiagent.engine.memory.MemoryWriteRequest;
import com.paiagent.engine.memory.SensitiveDataMasker;
import com.paiagent.entity.AgentMemory;
import com.paiagent.entity.AgentMemoryEmbedding;
import com.paiagent.mapper.AgentMemoryEmbeddingMapper;
import com.paiagent.mapper.AgentMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 长期记忆服务（迭代2：持久化分层记忆）。
 *
 * <p>能力：
 * <ul>
 *   <li>分层记忆：WORKING / SESSION / USER / TEAM，由 {@link MemoryScope} 定义；</li>
 *   <li>双存储：MySQL（agent_memory + agent_memory_embedding，权威源）+ Redis（易失层 WORKING/SESSION 会话级缓存）；</li>
 *   <li>脱敏：写入前对内容做 {@link SensitiveDataMasker} 脱敏；</li>
 *   <li>TTL：过期时间写入 expires_at，读取自动排除已过期项，并由 {@link #purgeExpired()} 定时逻辑删除。</li>
 * </ul>
 *
 * 为兼容历史调用方，保留原 {@code write(...)} / {@code retrieve(...)} 扁平签名；新方法使用
 * {@link MemoryWriteRequest} / {@link MemoryQueryRequest} 表达分层与 TTL 维度。
 */
@Slf4j
@Service
public class AgentMemoryService {

    private static final double SUBSTRING_SCORE = 0.5;
    private static final String REDIS_PREFIX = "mem:";

    private final AgentMemoryMapper memoryMapper;
    private final AgentMemoryEmbeddingMapper embeddingMapper;
    private final StringRedisTemplate redisTemplate;
    private final SensitiveDataMasker masker;
    private final ObjectMapper objectMapper;

    public AgentMemoryService(AgentMemoryMapper memoryMapper,
                              AgentMemoryEmbeddingMapper embeddingMapper,
                              StringRedisTemplate redisTemplate,
                              SensitiveDataMasker masker,
                              ObjectMapper objectMapper) {
        this.memoryMapper = memoryMapper;
        this.embeddingMapper = embeddingMapper;
        this.redisTemplate = redisTemplate;
        this.masker = masker;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 兼容旧签名：基于 scope 字符串写入，分层字段与 TTL 取默认值。
     */
    public Map<String, Object> write(String content, String memoryType, String scope,
                                     List<String> tags, String source, List<Double> embedding,
                                     String embeddingModel) {
        return write(MemoryWriteRequest.builder()
                .content(content)
                .memoryType(memoryType)
                .scope(scope)
                .tags(tags)
                .source(source)
                .embedding(embedding)
                .embeddingModel(embeddingModel)
                .build());
    }

    /**
     * 分层写入：落库 MySQL，易失层额外写入 Redis，并返回写入摘要。
     */
    public Map<String, Object> write(MemoryWriteRequest req) {
        MemoryScope ms = MemoryScope.from(req.getScope());

        String maskedContent = masker.mask(req.getContent());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = req.getExpiresAt();
        if (expiresAt == null && ms.defaultTtl() != null) {
            expiresAt = now.plus(ms.defaultTtl());
        }
        double importance = req.getImportance() != null ? req.getImportance() : 0.5;

        AgentMemory entity = new AgentMemory();
        entity.setScope(ms.code());
        entity.setMemoryType(StringUtils.hasText(req.getMemoryType()) ? req.getMemoryType() : "fact");
        entity.setContent(maskedContent);
        entity.setTags(joinTags(req.getTags()));
        entity.setSource(req.getSource());
        entity.setSessionId(req.getSessionId());
        entity.setUserId(req.getUserId());
        entity.setTenantId(req.getTenantId());
        entity.setImportance(importance);
        entity.setExpiresAt(expiresAt);
        entity.setEmbeddingModel(req.getEmbeddingModel());
        entity.setDeleted(0);
        memoryMapper.insert(entity);

        if (req.getEmbedding() != null && !req.getEmbedding().isEmpty()) {
            AgentMemoryEmbedding emb = new AgentMemoryEmbedding();
            emb.setMemoryId(entity.getId());
            emb.setModel(req.getEmbeddingModel());
            emb.setDimension(req.getEmbedding().size());
            emb.setEmbedding(toJson(req.getEmbedding()));
            embeddingMapper.insert(emb);
        }

        if (ms.isEphemeral()) {
            cacheToRedis(ms, req.getSessionId(), req.getUserId(), entity, req.getEmbedding(), expiresAt);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("memoryId", entity.getId());
        output.put("scope", ms.code());
        output.put("stored", true);
        output.put("sessionId", req.getSessionId());
        output.put("expiresAt", expiresAt);
        return output;
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /**
     * 兼容旧签名：基于 scope 字符串检索。
     */
    public Map<String, Object> retrieve(String query, String scope, List<String> tags,
                                        List<Double> queryEmbedding, int topK) {
        return retrieve(MemoryQueryRequest.builder()
                .query(query)
                .scope(scope)
                .tags(tags)
                .queryEmbedding(queryEmbedding)
                .topK(topK)
                .build());
    }

    /**
     * 分层检索：从 MySQL 拉取候选（已排除过期/已删除），合并 Redis 易失层，按相关性 + 重要性排序。
     */
    public Map<String, Object> retrieve(MemoryQueryRequest req) {
        QueryWrapper<AgentMemory> qw = new QueryWrapper<>();
        if (StringUtils.hasText(req.getScope())) {
            qw.eq("scope", MemoryScope.from(req.getScope()).code());
        }
        if (StringUtils.hasText(req.getSessionId())) {
            String sid = req.getSessionId();
            qw.and(w -> w.eq("session_id", sid).or().isNull("session_id"));
        }
        if (StringUtils.hasText(req.getUserId())) {
            String uid = req.getUserId();
            qw.and(w -> w.eq("user_id", uid).or().isNull("user_id"));
        }
        if (StringUtils.hasText(req.getTenantId())) {
            String tid = req.getTenantId();
            qw.and(w -> w.eq("tenant_id", tid).or().isNull("tenant_id"));
        }
        if (req.getTags() != null) {
            for (String tag : req.getTags()) {
                if (StringUtils.hasText(tag)) {
                    qw.and(w -> w.like("tags", tag));
                }
            }
        }
        // 排除已过期（TTL）与已逻辑删除
        qw.and(w -> w.isNull("expires_at").or().gt("expires_at", LocalDateTime.now()));
        qw.and(w -> w.ne("deleted", 1));
        qw.orderByDesc("importance", "created_at");

        List<AgentMemory> rows = memoryMapper.selectList(qw);

        // 合并 Redis 易失层（同会话最新写入，未过期的由 Redis TTL 保证）
        if (StringUtils.hasText(req.getSessionId()) && StringUtils.hasText(req.getScope())) {
            MemoryScope ms = MemoryScope.from(req.getScope());
            if (ms.isEphemeral()) {
                rows = mergeRedis(rows, ms, req.getSessionId(), req.getUserId());
            }
        }

        // 加载候选向量的内存表示，准备打分
        List<ScoredMemory> candidates = new ArrayList<>();
        for (AgentMemory row : rows) {
            candidates.add(toScored(row, loadEmbedding(row.getId())));
        }

        List<Double> queryEmbedding = req.getQueryEmbedding();
        String query = req.getQuery();
        boolean hasQuery = StringUtils.hasText(query);

        for (ScoredMemory c : candidates) {
            double score = cosine(queryEmbedding, c.embedding());
            if (score == 0 && hasQuery && c.content() != null
                    && c.content().toLowerCase().contains(query.toLowerCase())) {
                score = SUBSTRING_SCORE;
            }
            c.score(score);
        }

        int topK = Math.max(1, req.getTopK());
        List<ScoredMemory> matches = candidates.stream()
                .filter(c -> c.score() > 0)
                .sorted(Comparator.<ScoredMemory>comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(Comparator.comparing(ScoredMemory::importance).reversed()))
                .limit(topK)
                .toList();

        StringBuilder context = new StringBuilder();
        for (ScoredMemory m : matches) {
            context.append("- ").append(m.content()).append("\n");
        }

        List<Map<String, Object>> memories = new ArrayList<>();
        for (ScoredMemory m : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memoryId", m.id());
            item.put("scope", m.scope());
            item.put("memoryType", m.memoryType());
            item.put("content", m.content());
            item.put("tags", m.tags());
            item.put("source", m.source());
            item.put("score", m.score());
            memories.add(item);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("memories", memories);
        output.put("context", context.toString().trim());
        output.put("citations", matches.stream().map(ScoredMemory::id).toList());
        return output;
    }

    // ------------------------------------------------------------------
    // TTL 过期清理
    // ------------------------------------------------------------------

    /**
     * 定时清理已过期记忆（逻辑删除）。默认每 60 秒执行一次。
     *
     * @return 被清理（逻辑删除）的记录数
     */
    @Scheduled(fixedDelay = 60_000)
    public int purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AgentMemory> uw = new UpdateWrapper<>();
        uw.set("deleted", 1)
                .lt("expires_at", now)
                .and(w -> w.eq("deleted", 0).or().isNull("deleted"));
        int updated = memoryMapper.update(null, uw);
        if (updated > 0) {
            log.info("记忆 TTL 清理：逻辑删除 {} 条过期记录", updated);
        }
        return updated;
    }

    // ------------------------------------------------------------------
    // 标签工具（保留旧公共方法，供节点执行器调用）
    // ------------------------------------------------------------------

    public List<String> splitTags(String tagText) {
        if (!StringUtils.hasText(tagText)) {
            return new ArrayList<>();
        }
        String[] parts = tagText.split("[,，\\s]+");
        List<String> tags = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                tags.add(part.trim());
            }
        }
        return tags;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private List<AgentMemory> mergeRedis(List<AgentMemory> rows, MemoryScope ms, String sessionId, String userId) {
        List<AgentMemory> merged = new ArrayList<>(rows);
        try {
            String key = redisKey(ms, sessionId, userId);
            List<String> cached = redisTemplate.opsForList().range(key, 0, -1);
            if (cached == null || cached.isEmpty()) {
                return merged;
            }
            for (String json : cached) {
                ScoredMemory fromCache = parseCached(json);
                if (fromCache == null) {
                    continue;
                }
                boolean exists = merged.stream().anyMatch(r -> Objects.equals(r.getId(), fromCache.id()));
                if (!exists) {
                    merged.add(toEntity(fromCache));
                }
            }
        } catch (Exception e) {
            log.warn("合并 Redis 易失记忆失败，回退到 MySQL 结果：{}", e.getMessage());
        }
        return merged;
    }

    private void cacheToRedis(MemoryScope ms, String sessionId, String userId,
                              AgentMemory entity, List<Double> embedding, LocalDateTime expiresAt) {
        try {
            String key = redisKey(ms, sessionId, userId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", entity.getId());
            payload.put("scope", ms.code());
            payload.put("memoryType", entity.getMemoryType());
            payload.put("content", entity.getContent());
            payload.put("tags", splitTags(entity.getTags()));
            payload.put("source", entity.getSource());
            payload.put("sessionId", entity.getSessionId());
            payload.put("userId", entity.getUserId());
            payload.put("importance", entity.getImportance());
            payload.put("embedding", embedding);
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(payload));
            long ttlSeconds = expiresAt != null
                    ? Math.max(1, java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds())
                    : 1800;
            redisTemplate.expire(key, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("写入 Redis 易失记忆缓存失败（不影响落库）：{}", e.getMessage());
        }
    }

    private String redisKey(MemoryScope ms, String sessionId, String userId) {
        String suffix = StringUtils.hasText(sessionId) ? sessionId
                : (StringUtils.hasText(userId) ? userId : "global");
        return REDIS_PREFIX + ms.code() + ":" + suffix;
    }

    private List<Double> loadEmbedding(Long memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        QueryWrapper<AgentMemoryEmbedding> ew = new QueryWrapper<>();
        ew.eq("memory_id", memoryId).orderByDesc("id").last("LIMIT 1");
        AgentMemoryEmbedding emb = embeddingMapper.selectOne(ew);
        if (emb == null || !StringUtils.hasText(emb.getEmbedding())) {
            return List.of();
        }
        return toDoubleList(emb.getEmbedding());
    }

    private ScoredMemory toScored(AgentMemory row, List<Double> embedding) {
        return new ScoredMemory(row.getId(), row.getScope(), row.getMemoryType(), row.getContent(),
                splitTags(row.getTags()), row.getSource(), row.getSessionId(), row.getUserId(),
                row.getTenantId(), row.getImportance() == null ? 0.5 : row.getImportance(),
                row.getExpiresAt(), row.getEmbeddingModel(), embedding, 0.0);
    }

    private AgentMemory toEntity(ScoredMemory m) {
        AgentMemory row = new AgentMemory();
        row.setId(m.id());
        row.setScope(m.scope());
        row.setMemoryType(m.memoryType());
        row.setContent(m.content());
        row.setTags(joinTags(m.tags()));
        row.setSource(m.source());
        row.setSessionId(m.sessionId());
        row.setUserId(m.userId());
        row.setImportance(m.importance());
        return row;
    }

    private ScoredMemory parseCached(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Long id = map.get("id") == null ? null : Long.valueOf(String.valueOf(map.get("id")));
            String scope = (String) map.get("scope");
            String memoryType = (String) map.get("memoryType");
            String content = (String) map.get("content");
            Object tagsObj = map.get("tags");
            List<String> tags = tagsObj instanceof List ? (List<String>) tagsObj : List.of();
            String source = (String) map.get("source");
            String sessionId = (String) map.get("sessionId");
            String userId = (String) map.get("userId");
            Object impObj = map.get("importance");
            double importance = impObj == null ? 0.5 : Double.parseDouble(String.valueOf(impObj));
            List<Double> embedding = toDoubleListFromObject(map.get("embedding"));
            return new ScoredMemory(id, scope, memoryType, content, tags, source, sessionId, userId,
                    null, importance, null, null, embedding, 0.0);
        } catch (Exception e) {
            return null;
        }
    }

    private String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags);
    }

    private String toJson(List<Double> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Double> toDoubleList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Double> toDoubleListFromObject(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<Double> result = new ArrayList<>();
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            if (o instanceof Number n) {
                result.add(n.doubleValue());
            } else {
                try {
                    result.add(Double.parseDouble(o.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * 检索时的记忆视图（含运行期打分，可变）。
     */
    private static final class ScoredMemory {
        private final Long id;
        private final String scope;
        private final String memoryType;
        private final String content;
        private final List<String> tags;
        private final String source;
        private final String sessionId;
        private final String userId;
        private final String tenantId;
        private final double importance;
        private final LocalDateTime expiresAt;
        private final String embeddingModel;
        private final List<Double> embedding;
        private double score;

        ScoredMemory(Long id, String scope, String memoryType, String content, List<String> tags,
                     String source, String sessionId, String userId, String tenantId, double importance,
                     LocalDateTime expiresAt, String embeddingModel, List<Double> embedding, double score) {
            this.id = id;
            this.scope = scope;
            this.memoryType = memoryType;
            this.content = content;
            this.tags = tags;
            this.source = source;
            this.sessionId = sessionId;
            this.userId = userId;
            this.tenantId = tenantId;
            this.importance = importance;
            this.expiresAt = expiresAt;
            this.embeddingModel = embeddingModel;
            this.embedding = embedding;
            this.score = score;
        }

        Long id() { return id; }
        String scope() { return scope; }
        String memoryType() { return memoryType; }
        String content() { return content; }
        List<String> tags() { return tags; }
        String source() { return source; }
        String sessionId() { return sessionId; }
        String userId() { return userId; }
        double importance() { return importance; }
        List<Double> embedding() { return embedding; }
        double score() { return score; }
        void score(double value) { this.score = value; }
    }
}
