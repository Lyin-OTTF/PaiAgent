package com.paiagent.engine.langgraph.checkpoint;

import com.paiagent.mapper.LangGraphCheckpointMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LangGraphCheckpointSaver 单元测试（Mockito，无 DB）。
 *
 * 重点验证：
 * 1. put 的「无则插、有则改」幂等语义（langgraph4j 同一 checkpoint id 可能重复 put）；
 * 2. get 的两种寻址方式（checkPointId 精确 / threadId 取最新）；
 * 3. 状态 JSON 往返后仍是纯 Map / List（消费方按 Map 强转，不能残留 JSONObject）；
 * 4. 写库失败不阻断工作流（检查点只是恢复辅助能力）。
 */
class LangGraphCheckpointSaverTest {

    private LangGraphCheckpointMapper mapper;
    private LangGraphCheckpointSaver saver;

    @BeforeEach
    void setUp() {
        mapper = mock(LangGraphCheckpointMapper.class);
        saver = new LangGraphCheckpointSaver(mapper, 3);
    }

    @Test
    void shouldInsertWhenCheckpointAbsent() {
        when(mapper.selectById("cp-1")).thenReturn(null);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        saver.put(config, checkpoint("cp-1", "node-a", "node-b"));

        verify(mapper, times(1)).insert(any(LangGraphCheckpoint.class));
        verify(mapper, never()).updateById(any(LangGraphCheckpoint.class));
    }

    @Test
    void shouldUpdateWhenCheckpointAlreadyExists() {
        when(mapper.selectById("cp-1")).thenReturn(new LangGraphCheckpoint());

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        saver.put(config, checkpoint("cp-1", "node-a", "node-b"));

        verify(mapper, times(1)).updateById(any(LangGraphCheckpoint.class));
        verify(mapper, never()).insert(any(LangGraphCheckpoint.class));
    }

    @Test
    void shouldNotPropagateWriteFailure() {
        when(mapper.selectById("cp-1")).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(LangGraphCheckpoint.class));

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        // 写检查点失败不能让用户工作流失败
        assertDoesNotThrow(() -> saver.put(config, checkpoint("cp-1", "node-a", "node-b")));
    }

    @Test
    void shouldTrimOldCheckpointsAfterPut() {
        when(mapper.selectById("cp-1")).thenReturn(null);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        saver.put(config, checkpoint("cp-1", "node-a", "node-b"));

        // 保留策略：写入成功后按线程裁剪旧检查点（窗口 = 构造参数 3 → cutoffOffset = 2）
        verify(mapper, times(1)).deleteOldByThreadId("1001", 2);
    }

    @Test
    void shouldNotPropagateTrimFailure() {
        when(mapper.selectById("cp-1")).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(mapper).deleteOldByThreadId(anyString(), anyInt());

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        // 清理旧检查点失败同样不影响工作流执行与恢复
        assertDoesNotThrow(() -> saver.put(config, checkpoint("cp-1", "node-a", "node-b")));
        verify(mapper, times(1)).insert(any(LangGraphCheckpoint.class));
    }

    @Test
    void shouldClampRetainWindowToAtLeastOne() {
        LangGraphCheckpointSaver clamped = new LangGraphCheckpointSaver(mapper, 0);
        when(mapper.selectById("cp-1")).thenReturn(null);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        clamped.put(config, checkpoint("cp-1", "node-a", "node-b"));

        // 配置 0/负数时至少保留 1 条（最新一条是恢复的依赖）→ cutoffOffset = 0
        verify(mapper, times(1)).deleteOldByThreadId("1001", 0);
    }

    @Test
    void shouldGetByCheckPointIdWhenPresent() {
        LangGraphCheckpoint row = row("cp-1", "1001", "node-a", "node-b");
        when(mapper.selectById("cp-1")).thenReturn(row);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build();
        Optional<Checkpoint> found = saver.get(config);

        assertTrue(found.isPresent());
        assertEquals("cp-1", found.get().getId());
        assertEquals("node-a", found.get().getNodeId());
        assertEquals("node-b", found.get().getNextNodeId());
        verify(mapper, never()).selectLatestByThreadId(anyString());
    }

    @Test
    void shouldGetLatestByThreadWhenCheckPointIdAbsent() {
        when(mapper.selectLatestByThreadId("1001")).thenReturn(row("cp-9", "1001", "node-z", "node-y"));

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        Optional<Checkpoint> found = saver.get(config);

        assertTrue(found.isPresent());
        assertEquals("cp-9", found.get().getId());
    }

    @Test
    void shouldIgnoreCheckpointWithoutNextNode() {
        LangGraphCheckpoint row = row("cp-1", "1001", "node-a", null);
        when(mapper.selectById("cp-1")).thenReturn(row);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build();
        assertTrue(saver.get(config).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenRowMissing() {
        when(mapper.selectById("cp-1")).thenReturn(null);
        RunnableConfig config = RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build();
        assertTrue(saver.get(config).isEmpty());
    }

    @Test
    void shouldListAllCheckpointsOfThread() {
        when(mapper.selectByThreadId("1001")).thenReturn(List.of(
                row("cp-1", "1001", "node-a", "node-b"),
                row("cp-2", "1001", "node-b", "__END__")
        ));

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        assertEquals(2, saver.list(config).size());
    }

    @Test
    void shouldRoundTripStateAsPlainMapsAndLists() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("inputData", "hello");
        state.put("currentInput", Map.of("input", "hello", "count", 2));
        Map<String, Object> nodeOutputs = new LinkedHashMap<>();
        nodeOutputs.put("node-a", new LinkedHashMap<>(Map.of("response", List.of("a", "b"))));
        state.put("nodeOutputs", nodeOutputs);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").build();
        saver.put(config, Checkpoint.builder()
                .id("cp-1")
                .nodeId("node-a")
                .nextNodeId("node-b")
                .state(state)
                .build());

        // 抓取落库的 stateJson，反序列化后校验类型
        org.mockito.ArgumentCaptor<LangGraphCheckpoint> captor =
                org.mockito.ArgumentCaptor.forClass(LangGraphCheckpoint.class);
        verify(mapper).insert(captor.capture());
        String json = captor.getValue().getStateJson();

        LangGraphCheckpoint reloaded = row("cp-1", "1001", "node-a", "node-b");
        reloaded.setStateJson(json);
        when(mapper.selectById("cp-1")).thenReturn(reloaded);

        Optional<Checkpoint> restored = saver.get(
                RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build());

        assertTrue(restored.isPresent());
        Map<String, Object> restoredState = restored.get().getState();

        // 顶层必须是可写的 LinkedHashMap，而不是只读/异类型 Map
        assertInstanceOf(LinkedHashMap.class, restoredState);
        assertInstanceOf(Map.class, restoredState.get("currentInput"));
        assertInstanceOf(Map.class, restoredState.get("nodeOutputs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) restoredState.get("nodeOutputs");
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeA = (Map<String, Object>) outputs.get("node-a");
        assertInstanceOf(List.class, nodeA.get("response"));
        assertEquals(List.of("a", "b"), new ArrayList<>((List<?>) nodeA.get("response")));

        @SuppressWarnings("unchecked")
        Map<String, Object> currentInput = (Map<String, Object>) restoredState.get("currentInput");
        assertEquals(2, ((Number) currentInput.get("count")).intValue());
    }

    @Test
    void shouldTreatBlankStateJsonAsEmptyState() {
        LangGraphCheckpoint row = row("cp-1", "1001", "node-a", "node-b");
        row.setStateJson("   ");
        when(mapper.selectById("cp-1")).thenReturn(row);

        RunnableConfig config = RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build();
        Optional<Checkpoint> found = saver.get(config);
        assertTrue(found.isPresent());
        assertTrue(found.get().getState().isEmpty());
    }

    @Test
    void shouldNotFailWhenReadThrows() {
        when(mapper.selectById("cp-1")).thenThrow(new RuntimeException("db down"));
        RunnableConfig config = RunnableConfig.builder().threadId("1001").checkPointId("cp-1").build();
        assertTrue(saver.get(config).isEmpty());

        when(mapper.selectByThreadId("1001")).thenThrow(new RuntimeException("db down"));
        assertTrue(saver.list(config).isEmpty());
    }

    // ------------------------------------------------------------------

    private Checkpoint checkpoint(String id, String nodeId, String nextNodeId) {
        return Checkpoint.builder().id(id).nodeId(nodeId).nextNodeId(nextNodeId)
                .state(Map.of("inputData", "x")).build();
    }

    private LangGraphCheckpoint row(String id, String threadId, String nodeId, String nextNodeId) {
        LangGraphCheckpoint row = new LangGraphCheckpoint();
        row.setId(id);
        row.setThreadId(threadId);
        row.setNodeId(nodeId);
        row.setNextNodeId(nextNodeId);
        row.setStateJson("{}");
        return row;
    }
}
