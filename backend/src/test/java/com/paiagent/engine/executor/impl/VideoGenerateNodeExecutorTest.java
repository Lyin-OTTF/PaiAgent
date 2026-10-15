package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VideoGenerateNodeExecutor 单元测试。
 *
 * 设计要点：
 * 1. 使用 spy 包装被测对象，以便 stub 父类 {@code AbstractAgentPlanNodeExecutor} 的 protected 数据读取方法，
 *    从而隔离节点数据解析逻辑，避免 {@code node.getData()} 为 null 时的 NPE。
 * 2. 通过 stub {@code getVideoTask} 让首次轮询即返回终态，将每个用例的 sleep 控制在 1 次（5s）以内。
 * 3. 成功路径共触发 5 次进度回调：提交中 / 已提交 / 轮询中 / 完成 / 转存中。
 * 4. 使用 lenient 配置 {@code configResolver} 相关 stub，避免缺失 prompt 等提前抛异常用例触发
 *    STRICT_STUBS 的 UnnecessaryStubbingException。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoGenerateNodeExecutorTest {

    @Mock
    private AgentPlanConfigResolver configResolver;

    @Mock
    private VolcengineAgentPlanClient agentPlanClient;

    @Mock
    private MinioService minioService;

    @Mock
    private WorkflowNode node;

    @Mock
    private Consumer<ExecutionEvent> progressCallback;

    private VideoGenerateNodeExecutor executor;

    private ResolvedAgentPlanConfig mockConfig;

    @BeforeEach
    void setUp() {
        // 使用 spy 包装被测对象，以便 stub 父类 protected 的数据读取方法，隔离节点数据解析
        executor = spy(new VideoGenerateNodeExecutor(configResolver, agentPlanClient, minioService));
        // 保证任何真实走到父类的数据读取方法时 node.getData() 非空，避免 NPE
        when(node.getData()).thenReturn(new HashMap<>());

        mockConfig = mock(ResolvedAgentPlanConfig.class);
        when(mockConfig.model()).thenReturn("test-video-model");
        // provider 不能为 null：execute 内部用 Map.of 构建进度数据，null 值会触发 NPE。
        // 使用非 "agnes" 的值，确保走 agentPlanClient 路径。
        when(mockConfig.provider()).thenReturn("volcengine");

        // 缺失 prompt 等用例会在 resolve 之前抛异常，故使用 lenient 避免严格桩告警
        lenient().when(configResolver.resolve(any(WorkflowNode.class), eq("video"))).thenReturn(mockConfig);
        lenient().doNothing().when(configResolver).validateApiConfig(any(), anyString());
    }

    /**
     * 正常流程：提交任务 -> 首次轮询即成功 -> MinIO 转存成功。
     */
    @Test
    void execute_AllStepsSucceed_ReturnsOutputMap() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "A cat playing piano");
        doReturn("A cat playing piano").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        String taskId = "task-123";
        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(taskId);
        Map<String, Object> successTask = Map.of(
                "status", "success",
                "videoUrl", "http://origin.url/video.mp4",
                "coverUrl", "http://origin.url/cover.jpg");
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(successTask);
        String persistedUrl = "http://minio.url/videos/generated-task-123.mp4";
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(persistedUrl);

        Map<String, Object> result = executor.execute(node, input, progressCallback);

        assertNotNull(result);
        assertEquals(taskId, result.get("taskId"));
        assertEquals("succeeded", result.get("status"));
        assertEquals(persistedUrl, result.get("videoUrl"));
        assertEquals("test-video-model", result.get("model"));
        verify(progressCallback, times(5)).accept(any(ExecutionEvent.class));
    }

    /**
     * 缺少 prompt 时抛出 IllegalArgumentException。
     */
    @Test
    void execute_MissingPrompt_ThrowsIllegalArgumentException() {
        Map<String, Object> input = new HashMap<>();
        doReturn(null).when(executor).configuredTextInput(any(), any(), eq("prompt"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(node, input, progressCallback));
        assertTrue(exception.getMessage().contains("视频生成节点缺少 prompt"));
    }

    /**
     * 接口未返回 taskId 时抛出 IllegalStateException。
     */
    @Test
    void execute_ApiReturnsNoTaskId_ThrowsIllegalStateException() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test prompt");
        doReturn("test prompt").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn("");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, input, progressCallback));
        assertTrue(exception.getMessage().contains("视频生成接口未返回 taskId"));
    }

    /**
     * 任务状态变为失败（failed）时抛出 IllegalStateException。
     */
    @Test
    void execute_TaskStatusFailed_ThrowsIllegalStateException() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test prompt");
        doReturn("test prompt").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn("task-fail-id");
        Map<String, Object> failedTask = Map.of("status", "failed");
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(failedTask);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, input, progressCallback));
        assertTrue(exception.getMessage().contains("视频生成失败"));
    }

    /**
     * 轮询结束仍未成功、或未返回 videoUrl 时抛出 IllegalStateException。
     * 这里让首次轮询即返回 success 但无 videoUrl，等价覆盖"超时/无 URL"分支。
     */
    @Test
    void execute_NoVideoUrl_ThrowsIllegalStateException() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test prompt");
        doReturn("test prompt").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn("task-timeout-id");
        Map<String, Object> successNoUrl = Map.of("status", "success");
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(successNoUrl);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, input, progressCallback));
        assertTrue(exception.getMessage().contains("视频生成超时或未返回 videoUrl"));
    }

    /**
     * MinIO 转存失败时保留原始 URL 并正常返回。
     */
    @Test
    void execute_MinioUploadFails_ReturnsOriginalUrl() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test prompt");
        doReturn("test prompt").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        String taskId = "task-minio-fail";
        String originalUrl = "http://origin.url/video.mp4";
        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(taskId);
        Map<String, Object> successTask = Map.of("status", "success", "videoUrl", originalUrl);
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(successTask);
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("MinIO connection refused"));

        Map<String, Object> result = executor.execute(node, input, progressCallback);

        assertEquals(originalUrl, result.get("videoUrl"));
        assertEquals(originalUrl, result.get("output"));
    }

    /**
     * 成功状态判定应兼容 success / succeed / completed 等变体。
     */
    @Test
    void execute_StatusVariations_HandlesSuccessKeywords() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "Prompt");
        doReturn("Prompt").when(executor).configuredTextInput(any(), any(), eq("prompt"));

        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn("task-id");
        // 使用 "completed" 验证完成态关键词
        Map<String, Object> completedTask = Map.of("status", "completed", "videoUrl", "url");
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(completedTask);
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn("persisted");

        Map<String, Object> result = executor.execute(node, input, progressCallback);
        assertEquals("succeeded", result.get("status"));
    }

    /**
     * 验证进度回调按预期顺序与文案触发。
     */
    @Test
    void execute_ProgressCallback_TriggersCorrectly() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "progress test");
        doReturn("progress test").when(executor).configuredTextInput(any(), any(), eq("prompt"));
        when(node.getId()).thenReturn("node-1");
        when(node.getType()).thenReturn("video_generate");

        when(agentPlanClient.createVideoTask(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn("task-p");
        when(agentPlanClient.getVideoTask(any(), any())).thenReturn(Map.of("status", "success", "videoUrl", "url"));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn("persisted");

        executor.execute(node, input, progressCallback);

        ArgumentCaptor<ExecutionEvent> eventCaptor = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(progressCallback, times(5)).accept(eventCaptor.capture());
        List<ExecutionEvent> events = eventCaptor.getAllValues();
        assertEquals(5, events.size());
        assertTrue(events.get(0).getMessage().contains("视频任务提交中"));
        assertTrue(events.get(1).getMessage().contains("视频任务已提交"));
        assertTrue(events.get(2).getMessage().contains("视频生成中"));
        assertTrue(events.get(3).getMessage().contains("视频生成完成"));
        assertTrue(events.get(4).getMessage().contains("视频转存中"));
    }

    /**
     * 验证 getSupportedNodeType 返回正确的节点类型字符串。
     */
    @Test
    void getSupportedNodeType_ReturnsVideoGenerateType() {
        assertEquals("video_generate", executor.getSupportedNodeType());
    }
}
