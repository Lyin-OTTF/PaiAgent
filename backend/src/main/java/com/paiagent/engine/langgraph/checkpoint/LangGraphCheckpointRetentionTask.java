package com.paiagent.engine.langgraph.checkpoint;

import com.paiagent.mapper.LangGraphCheckpointMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * LangGraph 检查点保留策略的定时清理（迭代5 遗留治理）。
 *
 * <p>与 {@link LangGraphCheckpointSaver} 的单线程窗口清理互补：put 路径只约束
 * 「同一线程内最多保留 N 条」，无法阻止线程数量（= 执行次数）随业务增长而无限累积，
 * 故此定时任务按时间窗统一清理——删除创建时间早于 {@code retainDays} 天的检查点。
 *
 * <p>容错：清理失败只打 ERROR 不影响主业务；与记忆 TTL 清理同属
 * {@code MemorySchedulerConfig}（@EnableScheduling）驱动的定时能力，无需额外开关。
 */
@Slf4j
@Component
public class LangGraphCheckpointRetentionTask {

    private final LangGraphCheckpointMapper mapper;
    private final int retainDays;

    public LangGraphCheckpointRetentionTask(
            LangGraphCheckpointMapper mapper,
            @Value("${paiagent.langgraph.checkpoint-retain-days:7}") int retainDays) {
        this.mapper = mapper;
        this.retainDays = Math.max(1, retainDays);
    }

    /** 每小时执行一次：删除 retainDays 天前创建的检查点（执行完成 7 天后通常不再需要恢复）。 */
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpiredCheckpoints() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        try {
            int deleted = mapper.deleteExpiredBefore(cutoff);
            if (deleted > 0) {
                log.info("清理 {} 天前的 LangGraph 检查点: {} 条（截止时间 {}）", retainDays, deleted, cutoff);
            }
        } catch (Exception e) {
            // 清理失败不影响任何业务路径，下个周期重试
            log.error("清理过期 LangGraph 检查点失败（下周期重试）: cutoff={}", cutoff, e);
        }
    }
}
