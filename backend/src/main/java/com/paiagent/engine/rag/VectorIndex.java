package com.paiagent.engine.rag;

import java.util.List;

/**
 * 内存向量索引契约（cosine 语义）。
 * <p>迭代3 落地两个实现：{@link HnswVectorIndex}（近似最近邻，主路径）
 * 与 {@link BruteForceVectorIndex}（暴力基线，小数据/测试对照）。
 * 向量在写入与检索时统一归一化，cosine 相似度退化为点积。
 */
public interface VectorIndex {

    record ScoredVector(Long id, double score) {
    }

    /**
     * 写入或覆盖同 id 向量（同 id 覆盖 = 先删后插）。
     */
    void upsert(Long id, float[] vector);

    void remove(Long id);

    boolean contains(Long id);

    int size();

    /**
     * @return 按相似度降序的 topK（不足 topK 时返回全部）；相似度为 cosine（-1 ~ 1）
     */
    List<ScoredVector> search(float[] query, int topK);
}
