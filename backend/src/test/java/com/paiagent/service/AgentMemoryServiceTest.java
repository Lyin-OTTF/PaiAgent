package com.paiagent.service;

import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.engine.memory.MemoryWriteRequest;
import com.paiagent.mapper.AgentMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentMemoryService 集成测试（迭代2）。
 *
 * 启动完整 Spring 上下文，验证：
 *  - 分层记忆落库（MySQL）与 Redis 缓存；
 *  - 跨会话召回与同层会话隔离；
 *  - USER 层跨会话持久召回；
 *  - TTL 过期淘汰（读取排除 + 定时清理）；
 *  - 标签过滤。
 *
 * 使用 @Transactional 保证每个用例结束后 MySQL 回滚；Redis 缓存键均使用唯一样例，互不干扰。
 */
@SpringBootTest
@Transactional
class AgentMemoryServiceTest {

    @Autowired
    private AgentMemoryService memoryService;

    @Autowired
    private AgentMemoryMapper agentMemoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long writeSessionMemory(String sessionId, String content) {
        Map<String, Object> out = memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .scope("session")
                .sessionId(sessionId)
                .source("test")
                .tags(List.of("case-session"))
                .build());
        return (Long) out.get("memoryId");
    }

    @Test
    void crossSessionRecall_and_isolation() {
        String content = "session-A-secret-token-xyz";
        Long idA = writeSessionMemory("sess-A", content);

        // 同会话可召回
        Map<String, Object> sameSession = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("session")
                .sessionId("sess-A")
                .topK(5)
                .build());
        assertFalse(((List<?>) sameSession.get("memories")).isEmpty(), "同会话应能召回记忆");
        assertTrue(((List<?>) sameSession.get("citations")).contains(idA));

        // 不同会话隔离：不应召回 sess-A 的记忆
        Map<String, Object> otherSession = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("session")
                .sessionId("sess-B")
                .topK(5)
                .build());
        assertTrue(((List<?>) otherSession.get("memories")).isEmpty(), "不同会话应被隔离，召回为空");
    }

    @Test
    void userLayer_recallAcrossSessions() {
        String content = "user-u1-dark-mode-pref-xyz";
        Long id = (Long) memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .scope("user")
                .userId("u1")
                .source("test")
                .build()).get("memoryId");

        // 同一 userId，即便换了会话（sessionId 不同）仍能召回
        Map<String, Object> recall = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("user")
                .userId("u1")
                .sessionId("different-session")
                .topK(5)
                .build());
        assertFalse(((List<?>) recall.get("memories")).isEmpty());
        assertTrue(((List<?>) recall.get("citations")).contains(id));

        // 不同 userId 召回不到
        Map<String, Object> otherUser = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("user")
                .userId("u2")
                .topK(5)
                .build());
        assertTrue(((List<?>) otherUser.get("memories")).isEmpty());
    }

    @Test
    void ttl_exclusionAndPurge() {
        String content = "expired-memory-xyz";
        Long id = (Long) memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .scope("working")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .build()).get("memoryId");

        // 读取时已过期 -> 不应被召回
        Map<String, Object> recalled = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("working")
                .topK(5)
                .build());
        assertTrue(((List<?>) recalled.get("memories")).isEmpty(), "已过期的记忆读取时应被排除");

        // 定时清理 -> 逻辑删除
        int purged = memoryService.purgeExpired();
        assertTrue(purged >= 1, "至少清理 1 条过期记忆");

        // 全局逻辑删除下，MyBatis-Plus 的 selectById 会自动忽略已删除行；
        // 用原生 SQL 绕过逻辑删除校验 deleted 标记是否被正确置位。
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM agent_memory WHERE id = ?", Integer.class, id);
        assertNotNull(deleted, "过期记忆行应仍存在（逻辑删除，非物理删除）");
        assertEquals(1, deleted, "过期记忆应被逻辑删除（deleted=1）");
    }

    @Test
    void tagFilter() {
        String content = "tagged-fact-xyz";
        memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .scope("working")
                .tags(List.of("proj-x"))
                .build());

        Map<String, Object> hit = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("working")
                .tags(List.of("proj-x"))
                .topK(5)
                .build());
        assertFalse(((List<?>) hit.get("memories")).isEmpty(), "应按标签命中");

        Map<String, Object> miss = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("working")
                .tags(List.of("other-tag"))
                .topK(5)
                .build());
        assertTrue(((List<?>) miss.get("memories")).isEmpty(), "非匹配标签不应命中");
    }

    @Test
    void workingScope_redisCacheRoundTrip() {
        String content = "working-cache-xyz";
        Long id = (Long) memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .scope("working")
                .sessionId("ws-1")
                .source("test")
                .build()).get("memoryId");

        Map<String, Object> result = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(content)
                .scope("working")
                .sessionId("ws-1")
                .topK(5)
                .build());
        assertFalse(((List<?>) result.get("memories")).isEmpty(), "WORKING 层应经 Redis/MySQL 召回");
        assertTrue(((List<?>) result.get("citations")).contains(id));
    }

    // ------------------------------------------------------------------
    // 语义召回（embedding + cosine）路径覆盖（迭代2 遗留补齐）

    /**
     * 语义排序：cosine 分数高的记忆排前，即使查询词与内容零字面重叠。
     * 验证 embedding 落库 → 加载 → cosine 打分 → 排序全链路。
     */
    @Test
    void semanticRecall_cosineRanking() {
        List<Double> queryVec = List.of(1.0, 0.0, 0.0, 0.0);
        List<Double> nearVec = List.of(0.9, 0.1, 0.0, 0.0);   // 与查询几乎同向（cos≈0.99）
        List<Double> farVec = List.of(0.2, 0.1, 0.9, 0.9);    // 与查询有小夹角（cos≈0.14，仍为正可召回）

        memoryService.write(MemoryWriteRequest.builder()
                .content("语义近的记忆：苹果公司发布了新手机")
                .scope("working")
                .sessionId("sem-1")
                .source("test")
                .embedding(nearVec)
                .embeddingModel("test-embed")
                .build());
        memoryService.write(MemoryWriteRequest.builder()
                .content("语义远的记忆：香蕉含有钾元素")
                .scope("working")
                .sessionId("sem-1")
                .source("test")
                .embedding(farVec)
                .embeddingModel("test-embed")
                .build());

        // 查询词与两条内容都无字面子串，只有语义路能排序
        Map<String, Object> result = memoryService.retrieve(MemoryQueryRequest.builder()
                .query("水果品牌发布会")
                .scope("working")
                .sessionId("sem-1")
                .queryEmbedding(queryVec)
                .topK(5)
                .build());

        List<?> memories = (List<?>) result.get("memories");
        assertEquals(2, memories.size(), "两条带向量的记忆都应被 cosine 召回");
        Map<?, ?> first = (Map<?, ?>) memories.get(0);
        assertTrue(String.valueOf(first.get("content")).contains("苹果"),
                "cosine 分高的语义近记忆应排首位");
        double firstScore = ((Number) first.get("score")).doubleValue();
        double secondScore = ((Number) ((Map<?, ?>) memories.get(1)).get("score")).doubleValue();
        assertTrue(firstScore > secondScore, "应按 cosine 分降序");
    }

    /** embedding 落库验证：agent_memory_embedding 表应有对应行（维度/模型/向量值）。 */
    @Test
    void semanticRecall_embeddingPersisted() {
        memoryService.write(MemoryWriteRequest.builder()
                .content("带向量的记忆-xyz")
                .scope("working")
                .source("test")
                .embedding(List.of(0.5, 0.5, 0.5, 0.5))
                .embeddingModel("test-embed")
                .build());

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_memory_embedding e
                JOIN agent_memory m ON m.id = e.memory_id
                WHERE m.content = ? AND e.model = 'test-embed' AND e.dimension = 4
                """, Integer.class, "带向量的记忆-xyz");
        assertEquals(1, count, "写入的 embedding 应落 agent_memory_embedding 表");
    }

    /**
     * 降级路径：查询/记忆任一侧无向量时 cosine 为 0，退化为子串匹配 + importance 排序。
     * 这是生产常见形态（embedding 服务不可用时工具传空列表），必须有测试保护。
     */
    @Test
    void semanticRecall_degradesToSubstringWithoutEmbedding() {
        memoryService.write(MemoryWriteRequest.builder()
                .content("无向量记忆-子串可命中-xyz")
                .scope("working")
                .source("test")
                .build());

        Map<String, Object> hit = memoryService.retrieve(MemoryQueryRequest.builder()
                .query("子串可命中")
                .scope("working")
                .topK(5)
                .build());
        assertEquals(1, ((List<?>) hit.get("memories")).size(), "无向量时应经子串回退命中");

        Map<String, Object> miss = memoryService.retrieve(MemoryQueryRequest.builder()
                .query("完全不相关的查询词")
                .scope("working")
                .topK(5)
                .build());
        assertTrue(((List<?>) miss.get("memories")).isEmpty(), "无向量且无子串重叠时应无命中");
    }

    /**
     * WORKING 层 Redis 缓存的 embedding 往返：缓存 JSON 必须携带向量，
     * 否则 MySQL 行不可见时（缓存优先路径）语义打分会静默失效。
     */
    @Test
    @SuppressWarnings("unchecked")
    void semanticRecall_redisCacheCarriesEmbedding() throws Exception {
        List<Double> vec = List.of(1.0, 0.0, 0.0, 0.0);
        memoryService.write(MemoryWriteRequest.builder()
                .content("redis-向量往返-xyz")
                .scope("working")
                .sessionId("sem-redis-1")
                .source("test")
                .embedding(vec)
                .embeddingModel("test-embed")
                .build());

        // 直接读 Redis 缓存（key 规则 mem:{scope.code()}:{sessionId}，WORKING 为大写），断言 JSON 含 embedding
        List<String> cached = redisTemplate.opsForList().range("mem:WORKING:sem-redis-1", 0, -1);
        assertNotNull(cached);
        assertFalse(cached.isEmpty(), "WORKING 层应写 Redis 缓存");
        Map<String, Object> payload = objectMapper.readValue(cached.get(0), Map.class);
        assertEquals(vec, payload.get("embedding"), "Redis 缓存必须携带 embedding 向量");

        // 语义查询能命中（cosine = 1.0）
        Map<String, Object> result = memoryService.retrieve(MemoryQueryRequest.builder()
                .query("任意查询")
                .scope("working")
                .sessionId("sem-redis-1")
                .queryEmbedding(vec)
                .topK(5)
                .build());
        assertFalse(((List<?>) result.get("memories")).isEmpty(), "带 embedding 的记忆应被语义命中");
    }
}
