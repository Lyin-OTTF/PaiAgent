package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代3 评测夹具：确定性词哈希向量。
 * <p>同一批检索词产生同一向量方向，使「术语重叠度」成为可计算的相关性代理：
 * 查询与来源文档共享标题术语，与同主题干扰文档共享部分术语，指标因此落在有区分度的区间。
 * 仅用于离线评测，不参与生产检索。
 */
final class HashingEmbeddingFunction implements EmbeddingFunction {

    private static final int DIMENSION = 128;

    @Override
    public List<Double> embed(Long knowledgeBaseId, String text) {
        float[] vector = new float[DIMENSION];
        for (String term : ChineseAnalyzer.shared().terms(text)) {
            int hash = term.hashCode();
            int index = Math.floorMod(hash, DIMENSION);
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        List<Double> result = new ArrayList<>(DIMENSION);
        for (float value : vector) {
            result.add((double) value);
        }
        return result;
    }
}
