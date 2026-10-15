package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代3 性能基准：1 万条 128 维向量，验证规划文档约定的「检索 < 50ms」量级目标。
 * <p>预热 100 次后统计 200 次查询的 P50/P99；断言 P99 < 50ms。
 * 该基准为内存索引指标，不含 embedding 网络耗时（后者由 LLM 客户端自行观测）。
 */
class VectorIndexBenchmarkTest {

    private static final int COUNT = 10_000;
    private static final int DIMENSION = 128;
    private static final int WARMUP = 100;
    private static final int QUERIES = 200;

    @Test
    void shouldSearchTenThousandVectorsWithinBudget() {
        Random random = new Random(2026L);
        List<float[]> vectors = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            float[] vector = new float[DIMENSION];
            for (int j = 0; j < DIMENSION; j++) {
                vector[j] = random.nextFloat() - 0.5f;
            }
            vectors.add(vector);
        }
        List<float[]> queryVectors = new ArrayList<>(WARMUP + QUERIES);
        for (int i = 0; i < WARMUP + QUERIES; i++) {
            float[] vector = new float[DIMENSION];
            for (int j = 0; j < DIMENSION; j++) {
                vector[j] = random.nextFloat() - 0.5f;
            }
            queryVectors.add(vector);
        }

        HnswVectorIndex index = new HnswVectorIndex();
        long buildStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            index.upsert((long) i, vectors.get(i));
        }
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000L;

        for (int i = 0; i < WARMUP; i++) {
            index.search(queryVectors.get(i), 10);
        }
        List<Double> latencies = new ArrayList<>(QUERIES);
        for (int i = WARMUP; i < WARMUP + QUERIES; i++) {
            long start = System.nanoTime();
            index.search(queryVectors.get(i), 10);
            latencies.add((System.nanoTime() - start) / 1_000_000.0);
        }
        latencies.sort(Double::compareTo);
        double p50 = latencies.get((int) (latencies.size() * 0.5));
        double p99 = latencies.get((int) Math.ceil(latencies.size() * 0.99) - 1);

        System.out.printf("[RAG-Benchmark] HNSW 10k x %dd: build=%dms top10 P50=%.3fms P99=%.3fms%n",
                DIMENSION, buildMs, p50, p99);

        assertTrue(p99 < 50, "P99 应 < 50ms，实际 " + p99);
    }
}
