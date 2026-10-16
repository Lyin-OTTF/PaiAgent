package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.common.UserContext;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.WorkflowMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代7（评审报告 A1）：工作流数据归属隔离。
 *
 * <p>锁定三件事：写入必带归属、列表按「本人 + system」过滤、按 id 的读写先校验归属。
 */
class WorkflowServiceOwnershipTest {

    private WorkflowMapper mapper;
    private WorkflowService service;

    /**
     * LambdaQueryWrapper 需要实体的 TableInfo lambda 缓存；纯单元测试没有
     * Spring/MyBatis 容器，需显式初始化，否则会依测试执行顺序抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Workflow.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        service = new WorkflowService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建时写入当前登录用户作为归属")
    void createStampsCurrentOwner() {
        UserContext.setCurrent("alice");
        when(mapper.insert(any())).thenReturn(1);

        service.createWorkflow(request());

        ArgumentCaptor<Workflow> captor = ArgumentCaptor.forClass(Workflow.class);
        verify(mapper).insert(captor.capture());
        assertEquals("alice", captor.getValue().getOwner());
    }

    @Test
    @DisplayName("无登录上下文时归属兜底为 system（不产生 NULL owner 的孤儿数据）")
    void createFallsBackToSystemOwner() {
        when(mapper.insert(any())).thenReturn(1);

        service.createWorkflow(request());

        ArgumentCaptor<Workflow> captor = ArgumentCaptor.forClass(Workflow.class);
        verify(mapper).insert(captor.capture());
        assertEquals(UserContext.SYSTEM_OWNER, captor.getValue().getOwner());
    }

    @Test
    @DisplayName("列表查询限定「本人 + system」两种归属")
    void listFiltersByVisibleOwners() {
        UserContext.setCurrent("alice");
        when(mapper.selectList(any())).thenReturn(List.of());

        service.listWorkflows();

        ArgumentCaptor<Wrapper<Workflow>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<Workflow> wrapper = castToLambda(captor.getValue());
        assertTrue(wrapper.getSqlSegment().contains("owner"), "查询条件必须包含 owner 过滤");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("alice"), "应包含本人归属");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(UserContext.SYSTEM_OWNER), "应包含系统共享归属");
    }

    @Test
    @DisplayName("按 id 访问他人资源视为不存在（越权读取被拒绝）")
    void getByIdRejectsNotOwned() {
        UserContext.setCurrent("bob");
        lenient().when(mapper.selectOne(any())).thenReturn(null);
        lenient().when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.getWorkflowById(9L));
    }

    @Test
    @DisplayName("删除前校验归属：不属于当前用户则拒绝且不落删除")
    void deleteChecksOwnershipBeforeRemoving() {
        UserContext.setCurrent("bob");
        lenient().when(mapper.selectOne(any())).thenReturn(null);
        lenient().when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deleteWorkflow(9L));

        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("归属匹配时按 id 正常读取")
    void getByIdSucceedsWhenOwned() {
        UserContext.setCurrent("alice");
        Workflow owned = new Workflow();
        owned.setId(9L);
        owned.setName("wf");
        owned.setOwner("alice");
        // 注意：MyBatis-Plus 的 BaseMapper.selectOne 有单参/两参两个重载，
        // ServiceImpl.getOne(wrapper, true) 走的是两参版本，两处都要打桩。
        lenient().when(mapper.selectOne(any(), anyBoolean())).thenReturn(owned);
        lenient().when(mapper.selectOne(any())).thenReturn(owned);
        lenient().when(mapper.selectList(any())).thenReturn(List.of(owned));

        assertEquals(9L, service.getWorkflowById(9L).getId());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Workflow> castToLambda(Wrapper<Workflow> wrapper) {
        return (LambdaQueryWrapper<Workflow>) wrapper;
    }

    private WorkflowRequest request() {
        WorkflowRequest request = new WorkflowRequest();
        request.setName("wf");
        request.setFlowData("{}");
        request.setEngineType("dag");
        return request;
    }
}
