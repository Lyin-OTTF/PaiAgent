package com.paiagent.engine.scheduler;

import lombok.Builder;
import lombok.Data;

/**
 * 调度器配置
 */
@Data
@Builder
public class SchedulerConfig {

    /** 并行度（线程池大小，即有界并发的上限） */
    @Builder.Default
    private int poolSize = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /** 调度线程名前缀 */
    @Builder.Default
    private String threadNamePrefix = "dag-scheduler-";

    /** 关闭线程池时的等待秒数 */
    @Builder.Default
    private long shutdownTimeoutSeconds = 30L;

    public static SchedulerConfig defaults() {
        return builder().build();
    }
}
