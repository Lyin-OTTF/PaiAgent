package com.paiagent.engine.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分层记忆检索请求（迭代2）。
 * sessionId/userId/tenantId 为空时表示该维度不做强约束（仍受 scope 过滤）。
 */
@Data
@Builder
public class MemoryQueryRequest {
    /** 检索文本（用于子串匹配兜底） */
    String query;
    /** 分层范围字符串；为空表示不限范围 */
    String scope;
    /** 标签过滤（需全部命中） */
    List<String> tags;
    /** 会话 ID（SESSION/WORKING 层隔离用） */
    String sessionId;
    /** 用户 ID（USER 层隔离用） */
    String userId;
    /** 租户 ID（TEAM 层隔离用） */
    String tenantId;
    /** 查询向量 */
    List<Double> queryEmbedding;
    /** 返回条数，默认 5 */
    int topK;
}
