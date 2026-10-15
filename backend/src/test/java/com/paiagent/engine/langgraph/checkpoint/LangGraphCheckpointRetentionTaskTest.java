package com.paiagent.engine.langgraph.checkpoint;

import com.paiagent.mapper.LangGraphCheckpointMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LangGraphCheckpointRetentionTask 单元测试（Mockito，无 DB）。
 *
 * 重点验证：
 * 1. 清理截止时间 = now - retainDays（时间窗语义正确）；
 * 2. 无过期数据时不调删除（mapper 返回 0 仅代表没删，不应报错）；
 * 3. 清理失败不向上抛（定时任务异常只打日志，下周期重试）。
 */
class LangGraphCheckpointRetentionTaskTest {

    private LangGraphCheckpointMapper mapper;
    private LangGraphCheckpointRetentionTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(LangGraphCheckpointMapper.class);
        task = new LangGraphCheckpointRetentionTask(mapper, 7);
    }

    @Test
    void shouldDeleteCheckpointsOlderThanRetainWindow() {
        when(mapper.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(3);

        task.purgeExpiredCheckpoints();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, times(1)).deleteExpiredBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        // 截止时间应约为 7 天前（允许调度误差秒级）
        LocalDateTime expected = LocalDateTime.now().minusDays(7);
        long diffSeconds = Math.abs(java.time.Duration.between(expected, cutoff).toSeconds());
        assertTrue(diffSeconds < 5, "截止时间应为 7 天前，实际偏差 " + diffSeconds + " 秒");
    }

    @Test
    void shouldSucceedWhenNothingExpired() {
        when(mapper.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(0);

        // 无过期数据时静默跳过（不抛异常、不重复删除）
        assertDoesNotThrow(task::purgeExpiredCheckpoints);
        verify(mapper, times(1)).deleteExpiredBefore(any(LocalDateTime.class));
    }

    @Test
    void shouldNotPropagatePurgeFailure() {
        doThrow(new RuntimeException("db down")).when(mapper).deleteExpiredBefore(any(LocalDateTime.class));

        // 定时清理失败不能冒泡（否则只影响日志噪音，不应中断调度器）
        assertDoesNotThrow(task::purgeExpiredCheckpoints);
    }

    @Test
    void shouldClampRetainDaysToAtLeastOne() {
        LangGraphCheckpointRetentionTask clamped = new LangGraphCheckpointRetentionTask(mapper, 0);
        when(mapper.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(1);

        clamped.purgeExpiredCheckpoints();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, times(1)).deleteExpiredBefore(captor.capture());
        LocalDateTime expected = LocalDateTime.now().minusDays(1);
        long diffSeconds = Math.abs(java.time.Duration.between(expected, captor.getValue()).toSeconds());
        assertTrue(diffSeconds < 5, "截止时间应为 1 天前，实际偏差 " + diffSeconds + " 秒");
    }
}
