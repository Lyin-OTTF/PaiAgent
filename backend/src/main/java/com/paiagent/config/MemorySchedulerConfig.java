package com.paiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 记忆相关定时任务开关（迭代2：TTL 过期清理）。
 */
@Configuration
@EnableScheduling
public class MemorySchedulerConfig {
}
