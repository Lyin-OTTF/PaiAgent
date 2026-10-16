package com.paiagent.controller;

import com.paiagent.common.GlobalExceptionHandler;
import com.paiagent.engine.EngineSelector;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.ExecutionVariableMapper;
import com.paiagent.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代9 P0-2：执行快照 / 执行变量接口的归属校验。
 *
 * <p>评审报告第九章 🔴2：这两个端点原先只校验 {@code id == record.getFlowId()}，
 * 未做工作流归属校验——枚举自增 executionId 即可读取他人的节点快照与执行变量
 * （含用户输入与 LLM 输出）。
 */
class ExecutionControllerTest {

    private WorkflowService workflowService;
    private ExecutionRecordMapper executionRecordMapper;
    private ExecutionSnapshotMapper executionSnapshotMapper;
    private ExecutionVariableMapper executionVariableMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        executionRecordMapper = mock(ExecutionRecordMapper.class);
        executionSnapshotMapper = mock(ExecutionSnapshotMapper.class);
        executionVariableMapper = mock(ExecutionVariableMapper.class);

        ExecutionController controller = new ExecutionController();
        ReflectionTestUtils.setField(controller, "workflowService", workflowService);
        ReflectionTestUtils.setField(controller, "engineSelector", mock(EngineSelector.class));
        ReflectionTestUtils.setField(controller, "executionRecordMapper", executionRecordMapper);
        ReflectionTestUtils.setField(controller, "executionSnapshotMapper", executionSnapshotMapper);
        ReflectionTestUtils.setField(controller, "executionVariableMapper", executionVariableMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("快照接口：工作流不属于当前用户 → 拒绝，且不查询快照")
    void snapshotsRejectForeignWorkflow() throws Exception {
        when(workflowService.getOwnedWorkflow(1L))
                .thenThrow(new IllegalArgumentException("工作流不存在或无权访问"));

        mockMvc.perform(get("/api/workflows/1/executions/9/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("工作流不存在或无权访问"));

        verify(executionRecordMapper, never()).selectById(any());
        verify(executionSnapshotMapper, never()).selectByExecutionId(any());
    }

    @Test
    @DisplayName("变量接口：工作流不属于当前用户 → 拒绝，且不查询变量")
    void variablesRejectForeignWorkflow() throws Exception {
        when(workflowService.getOwnedWorkflow(1L))
                .thenThrow(new IllegalArgumentException("工作流不存在或无权访问"));

        mockMvc.perform(get("/api/workflows/1/executions/9/variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("工作流不存在或无权访问"));

        verify(executionVariableMapper, never()).selectByExecutionId(any());
    }

    @Test
    @DisplayName("快照接口：本人工作流正常返回（归属校验不误伤）")
    void snapshotsReturnDataForOwnedWorkflow() throws Exception {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        when(workflowService.getOwnedWorkflow(1L)).thenReturn(workflow);

        ExecutionRecord record = new ExecutionRecord();
        record.setId(9L);
        record.setFlowId(1L);
        when(executionRecordMapper.selectById(9L)).thenReturn(record);
        when(executionSnapshotMapper.selectByExecutionId(9L)).thenReturn(List.of());

        mockMvc.perform(get("/api/workflows/1/executions/9/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("快照接口：executionId 与 flowId 不匹配 → 视为不存在")
    void snapshotsRejectMismatchedFlowId() throws Exception {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        when(workflowService.getOwnedWorkflow(1L)).thenReturn(workflow);

        ExecutionRecord record = new ExecutionRecord();
        record.setId(9L);
        record.setFlowId(2L);
        when(executionRecordMapper.selectById(9L)).thenReturn(record);

        mockMvc.perform(get("/api/workflows/1/executions/9/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("执行记录不存在"));

        verify(executionSnapshotMapper, never()).selectByExecutionId(any());
    }
}
