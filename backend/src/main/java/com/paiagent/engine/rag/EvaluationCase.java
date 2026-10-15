package com.paiagent.engine.rag;

import java.util.List;

/**
 * 单条离线评测用例：查询 + 应被召回的 chunk 集合。
 *
 * @param query            自然语言查询
 * @param relevantChunkIds 相关分片 ID（命中任一即算命中；Recall 分母为集合大小）
 * @param topic            主题标签（分组统计用，可为 null）
 * @param note             备注（生成来源/人工校验说明）
 */
public record EvaluationCase(
        String query,
        List<Long> relevantChunkIds,
        String topic,
        String note
) {
}
