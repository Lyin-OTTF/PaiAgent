package com.paiagent.engine.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迭代3 RAG 检索参数。配置前缀 {@code paiagent.rag}，见 application.yml。
 */
@ConfigurationProperties(prefix = "paiagent.rag")
public class RagProperties {

    /** 混合检索总开关：关闭时 KnowledgeBaseService 回退到原全表扫描实现 */
    private boolean hybridEnabled = true;

    /** 候选倍数：每路召回 topK * candidateFactor 条候选进入融合 */
    private int candidateFactor = 10;

    /** RRF 平滑常数 k，越大路间排名差异越被压缩；60 为论文常用值 */
    private int rrfK = 60;

    /** 重排权重：RRF 归一化分 / 正文查询词覆盖率 / 标题查询词覆盖率 */
    private double rrfWeight = 0.4;
    private double coverageWeight = 0.4;
    private double titleWeight = 0.2;

    /**
     * 单个向量索引桶的最大向量数（迭代8 · 报告🟡8 后半：HNSW 内存无上限的参数化兜底）。
     *
     * <p>HNSW 索引按「知识库 × 维度」分桶懒加载，全量 chunk 进内存；单桶超限时写入显式失败
     * （{@link HybridRetriever} 对向量路异常安全兜底，检索退化为词法路），而非静默驱逐或无限增长。
     */
    private int maxVectors = 100_000;

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public int candidateFactor() {
        return candidateFactor;
    }

    public void setCandidateFactor(int candidateFactor) {
        this.candidateFactor = candidateFactor;
    }

    public int rrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double rrfWeight() {
        return rrfWeight;
    }

    public void setRrfWeight(double rrfWeight) {
        this.rrfWeight = rrfWeight;
    }

    public double coverageWeight() {
        return coverageWeight;
    }

    public void setCoverageWeight(double coverageWeight) {
        this.coverageWeight = coverageWeight;
    }

    public double titleWeight() {
        return titleWeight;
    }

    public void setTitleWeight(double titleWeight) {
        this.titleWeight = titleWeight;
    }

    public int maxVectors() {
        return maxVectors;
    }

    public void setMaxVectors(int maxVectors) {
        this.maxVectors = maxVectors;
    }
}
