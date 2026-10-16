package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.entity.NodeDefinition;
import com.paiagent.mapper.NodeDefinitionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代7（评审报告 P2 #11）：节点定义服务此前零测试保护。
 *
 * <p>锁定三条既有约定：内置节点（llm/memory/knowledge/图片/视频）在库中缺失时补齐；
 * 独立 Agent 类节点对画布隐藏；{@code llm}/{@code react_agent} 走内置定义不查库。
 */
class NodeDefinitionServiceTest {

    private NodeDefinitionMapper mapper;
    private NodeDefinitionService service;

    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NodeDefinition.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(NodeDefinitionMapper.class);
        service = new NodeDefinitionService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    @DisplayName("库中缺失的内置节点被补齐，独立 Agent 节点对调色板隐藏")
    void listMergesBuiltInsAndHidesStandaloneAgents() {
        NodeDefinition custom = node("http_request", "HTTP 请求", "TOOL");
        NodeDefinition hidden = node("web_fetch", "网页读取", "TOOL");
        lenient().when(mapper.selectList(any())).thenReturn(List.of(custom, hidden));

        List<NodeDefinition> definitions = service.listAllNodeDefinitions();
        List<String> types = definitions.stream().map(NodeDefinition::getNodeType).toList();

        assertTrue(types.contains("http_request"), "库中自定义节点应保留");
        assertTrue(types.contains("llm"), "缺失的内置 llm 节点应补齐");
        assertTrue(types.contains("image_generate"), "缺失的内置图片节点应补齐");
        assertTrue(!types.contains("web_fetch"), "独立 Agent 节点不应出现在调色板");
        assertTrue(!types.contains("react_agent"), "react_agent 不应出现在调色板");
        // 迭代7：memory_write/knowledge_* 属独立 Agent 节点（仅在 LLM 节点的工具配置里使用），
        // 刻意不进调色板；其原先的内置注入因此不可达，已随本批次一并删除。
        assertTrue(!types.contains("memory_write"), "独立 Agent 节点不应出现在调色板");
        assertTrue(!types.contains("knowledge_upsert"), "独立 Agent 节点不应出现在调色板");
    }

    @Test
    @DisplayName("库中已有的 llm 节点保留库中版本（内置仅作兜底）")
    void listPrefersStoredDefinition() {
        NodeDefinition stored = node("llm", "自定义大模型节点", "LLM");
        lenient().when(mapper.selectList(any())).thenReturn(List.of(stored));

        List<NodeDefinition> definitions = service.listAllNodeDefinitions();
        NodeDefinition llm = definitions.stream()
                .filter(node -> "llm".equals(node.getNodeType()))
                .findFirst()
                .orElseThrow();

        assertSame(stored, llm);
    }

    @Test
    @DisplayName("查询 llm/react_agent 走内置定义，不查库")
    void builtInLookupSkipsDatabase() {
        NodeDefinition llm = service.getByNodeType("llm");
        assertEquals("llm", llm.getNodeType());
        assertNotNull(llm.getConfigSchema());

        NodeDefinition react = service.getByNodeType("react_agent");
        assertEquals("react_agent", react.getNodeType());

        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("其他类型回落到库中查询")
    void customTypeQueriesDatabase() {
        NodeDefinition stored = node("http_request", "HTTP 请求", "TOOL");
        lenient().when(mapper.selectOne(any())).thenReturn(stored);

        assertSame(stored, service.getByNodeType("http_request"));
    }

    private NodeDefinition node(String nodeType, String displayName, String category) {
        NodeDefinition node = new NodeDefinition();
        node.setNodeType(nodeType);
        node.setDisplayName(displayName);
        node.setCategory(category);
        return node;
    }
}
