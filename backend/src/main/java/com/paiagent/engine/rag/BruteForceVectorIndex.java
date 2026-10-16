package com.paiagent.engine.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴力向量索引：全量扫描 + 小顶堆取 topK。
 * <p>作为 {@link HnswVectorIndex} 的对照基线（单测 recall 校验）与小数据量路径，
 * 万级数据下延迟随 N 线性增长，仅用于测试与回退。
 */
public class BruteForceVectorIndex implements VectorIndex {

    private record Node(Long id, float[] vector) {
    }

    private final Map<Long, float[]> vectors = new LinkedHashMap<>();

    // 迭代7（报告低危项）：本类作为对照实现同样可能被并发访问，
    // 而 LinkedHashMap 非线程安全（并发 put/get 可能结构损坏或死循环），故统一加锁。

    @Override
    public synchronized void upsert(Long id, float[] vector) {
        if (id == null || vector == null || vector.length == 0) {
            return;
        }
        vectors.put(id, normalize(vector));
    }

    @Override
    public synchronized void remove(Long id) {
        vectors.remove(id);
    }

    @Override
    public synchronized boolean contains(Long id) {
        return vectors.containsKey(id);
    }

    @Override
    public synchronized int size() {
        return vectors.size();
    }

    @Override
    public synchronized List<ScoredVector> search(float[] query, int topK) {
        // 迭代7（报告低危项）：补 query 空值防御（normalize(null) 会 NPE）
        if (query == null || query.length == 0 || topK <= 0) {
            return List.of();
        }
        float[] q = normalize(query);
        if (q == null) {
            return List.of(); // 零向量无方向，与 HnswVectorIndex 行为一致
        }
        PriorityQueueHolder holder = new PriorityQueueHolder(topK);
        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            double score = dot(q, entry.getValue());
            holder.offer(entry.getKey(), score);
        }
        return holder.sortedResults();
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm <= 0) {
            return null; // 零向量：无方向，检索返回空
        }
        double factor = 1.0 / Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] * factor);
        }
        return normalized;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        int size = Math.min(a.length, b.length);
        for (int i = 0; i < size; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * 固定容量小顶堆：堆顶是当前最差结果，超容量即淘汰。
     */
    private static final class PriorityQueueHolder {
        private final java.util.PriorityQueue<ScoredVector> minHeap;
        private final int capacity;

        PriorityQueueHolder(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.minHeap = new java.util.PriorityQueue<>(Comparator.comparingDouble(ScoredVector::score));
        }

        void offer(Long id, double score) {
            if (minHeap.size() < capacity) {
                minHeap.offer(new ScoredVector(id, score));
                return;
            }
            if (minHeap.peek().score() < score) {
                minHeap.poll();
                minHeap.offer(new ScoredVector(id, score));
            }
        }

        List<ScoredVector> sortedResults() {
            List<ScoredVector> results = new ArrayList<>(minHeap);
            results.sort(Comparator.comparingDouble(ScoredVector::score).reversed());
            return results;
        }
    }
}
