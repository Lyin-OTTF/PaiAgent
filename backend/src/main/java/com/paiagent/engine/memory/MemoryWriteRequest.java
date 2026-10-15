package com.paiagent.engine.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分层记忆写入请求（迭代2）。
 * 兼容旧 {@code write(...)} 的扁平参数；新增 sessionId/userId/tenantId/importance/expiresAt 以支持分层与 TTL。
 */
@Data
@Builder
public class MemoryWriteRequest {
    /** 原始内容（写入前会被脱敏） */
    String content;
    /** 记忆类型，默认 fact */
    String memoryType;
    /** 分层范围字符串，如 working/session/user/team，默认 working */
    String scope;
    /** 标签列表 */
    List<String> tags;
    /** 来源标识 */
    String source;
    /** 会话 ID（SESSION/WORKING 层） */
    String sessionId;
    /** 用户 ID（USER 层） */
    String userId;
    /** 租户 ID（TEAM 层） */
    String tenantId;
    /** 向量 */
    List<Double> embedding;
    /** 向量模型 */
    String embeddingModel;
    /** 重要性权重，默认 0.5 */
    Double importance;
    /** 显式过期时间；为空时按 MemoryScope 默认 TTL 计算 */
    LocalDateTime expiresAt;
}
