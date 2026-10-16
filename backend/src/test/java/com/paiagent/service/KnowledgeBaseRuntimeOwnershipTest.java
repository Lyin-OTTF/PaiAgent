package com.paiagent.service;

import com.paiagent.common.UserContext;
import com.paiagent.engine.rag.HybridRetriever;
import com.paiagent.engine.rag.RagProperties;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeIndexTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代9 P0-3：知识库「运行时路径」归属隔离。
 *
 * <p>评审报告第九章 🔴3：{@code requireBaseUnscoped}/{@code resolveBaseForRuntime}/
 * {@code listDocuments} 只做存在性校验——用户可在自己工作流里把 {@code knowledgeBaseId}
 * 指向他人私有知识库做检索/写入，归属隔离在运行时被击穿。
 */
class KnowledgeBaseRuntimeOwnershipTest {

    private KnowledgeBaseMapper baseMapper;
    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeBaseService service;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void buildService(KnowledgeBase base) {
        baseMapper = mock(KnowledgeBaseMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        when(baseMapper.selectById(base.getId())).thenReturn(base);
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        RagProperties properties = new RagProperties();
        properties.setHybridEnabled(false); // 走 legacy 路径，聚焦归属校验

        service = new KnowledgeBaseService(
                baseMapper,
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeIndexTaskMapper.class),
                mock(AgentPlanConfigResolver.class),
                mock(VolcengineAgentPlanClient.class),
                mock(HybridRetriever.class),
                properties
        );
    }

    private KnowledgeBase base(Long id, String owner) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setOwner(owner);
        kb.setName("kb-" + id);
        kb.setEmbeddingModel("m");
        return kb;
    }

    @Test
    @DisplayName("运行时检索：他人私有知识库被拒绝")
    void runtimeSearchRejectsForeignKnowledgeBase() {
        buildService(base(1L, "alice"));
        UserContext.setCurrent("bob");

        assertThrows(IllegalArgumentException.class,
                () -> service.searchRuntime("1", "q", 5, 0.2));
    }

    @Test
    @DisplayName("运行时检索：本人知识库放行")
    void runtimeSearchAllowsOwnKnowledgeBase() {
        buildService(base(1L, "alice"));
        UserContext.setCurrent("alice");

        assertDoesNotThrow(() -> service.searchRuntime("1", "q", 5, 0.2));
    }

    @Test
    @DisplayName("运行时检索：system 资源对所有用户可见")
    void runtimeSearchAllowsSystemKnowledgeBase() {
        buildService(base(1L, UserContext.SYSTEM_OWNER));
        UserContext.setCurrent("bob");

        assertDoesNotThrow(() -> service.searchRuntime("1", "q", 5, 0.2));
    }

    @Test
    @DisplayName("无归属的历史数据（owner=null）按系统资源共享，不致误伤既有部署")
    void runtimeSearchAllowsLegacyNullOwner() {
        buildService(base(1L, null));
        UserContext.setCurrent("bob");

        assertDoesNotThrow(() -> service.searchRuntime("1", "q", 5, 0.2));
    }

    @Test
    @DisplayName("文档列表：他人私有知识库被拒绝（修复前可枚举读取）")
    void listDocumentsRejectsForeignKnowledgeBase() {
        buildService(base(1L, "alice"));
        UserContext.setCurrent("bob");

        assertThrows(IllegalArgumentException.class, () -> service.listDocuments(1L));
    }

    @Test
    @DisplayName("文档列表：本人知识库放行")
    void listDocumentsAllowsOwnKnowledgeBase() {
        buildService(base(1L, "alice"));
        UserContext.setCurrent("alice");

        assertDoesNotThrow(() -> service.listDocuments(1L));
    }

    @Test
    @DisplayName("运行时写入：他人私有知识库被拒绝")
    void runtimeUpsertRejectsForeignKnowledgeBase() {
        buildService(base(1L, "alice"));
        UserContext.setCurrent("bob");

        assertThrows(IllegalArgumentException.class,
                () -> service.upsert("1", "title", "content", null, List.of(), List.of(), "m"));
    }
}
