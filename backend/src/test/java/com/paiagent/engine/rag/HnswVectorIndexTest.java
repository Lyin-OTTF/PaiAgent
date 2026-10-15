package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswVectorIndexTest {

    private static final int DIMENSION = 64;
    private static final int COUNT = 1000;

    private final Random random = new Random(42L);

    private float[] randomVector() {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat() - 0.5f;
        }
        return vector;
    }

    private List<float[]> seed(int count) {
        List<float[]> vectors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            vectors.add(randomVector());
        }
        return vectors;
    }

    @Test
    void shouldRecallTrueNeighborsAgainstBruteForce() {
        List<float[]> vectors = seed(COUNT);
        HnswVectorIndex hnsw = new HnswVectorIndex();
        BruteForceVectorIndex brute = new BruteForceVectorIndex();
        for (int i = 0; i < vectors.size(); i++) {
            hnsw.upsert((long) i, vectors.get(i));
            brute.upsert((long) i, vectors.get(i));
        }
        assertEquals(COUNT, hnsw.size());

        double recallSum = 0;
        int queries = 50;
        for (int q = 0; q < queries; q++) {
            float[] query = randomVector();
            List<VectorIndex.ScoredVector> expected = brute.search(query, 10);
            List<VectorIndex.ScoredVector> actual = hnsw.search(query, 10);
            Set<Long> expectedIds = new HashSet<>();
            for (VectorIndex.ScoredVector v : expected) {
                expectedIds.add(v.id());
            }
            long hits = actual.stream().filter(v -> expectedIds.contains(v.id())).count();
            recallSum += (double) hits / expected.size();
        }
        double recall = recallSum / queries;
        // 简化 HNSW 在 1000 点 64 维下的工程下限；低于此说明图结构或搜索有缺陷
        assertTrue(recall >= 0.95, "recall@10 应 >= 0.95，实际 " + recall);
    }

    @Test
    void shouldFindExactMatchAsTopHit() {
        HnswVectorIndex index = new HnswVectorIndex();
        List<float[]> vectors = seed(200);
        for (int i = 0; i < vectors.size(); i++) {
            index.upsert((long) i, vectors.get(i));
        }
        float[] target = vectors.get(123);
        List<VectorIndex.ScoredVector> hits = index.search(target, 5);
        assertEquals(123L, hits.getFirst().id(), "精确匹配应为 top1");
        assertTrue(hits.getFirst().score() > 0.99, "自身 cosine 应接近 1");
    }

    @Test
    void shouldRemoveVectorFromSearch() {
        HnswVectorIndex index = new HnswVectorIndex();
        List<float[]> vectors = seed(100);
        for (int i = 0; i < vectors.size(); i++) {
            index.upsert((long) i, vectors.get(i));
        }
        index.remove(5L);
        assertFalse(index.contains(5L));
        assertEquals(99, index.size());
        for (VectorIndex.ScoredVector hit : index.search(vectors.get(5), 10)) {
            assertFalse(hit.id() == 5L, "已删除向量不应出现在结果中");
        }
    }

    @Test
    void shouldOverwriteSameId() {
        HnswVectorIndex index = new HnswVectorIndex();
        float[] first = randomVector();
        float[] second = randomVector();
        index.upsert(1L, first);
        index.upsert(1L, second);
        assertEquals(1, index.size());
        List<VectorIndex.ScoredVector> hits = index.search(second, 1);
        assertEquals(1L, hits.getFirst().id());
        assertTrue(hits.getFirst().score() > 0.99);
    }

    @Test
    void shouldReturnEmptyForEmptyIndexOrBadQuery() {
        HnswVectorIndex index = new HnswVectorIndex();
        assertTrue(index.search(randomVector(), 5).isEmpty());
        assertTrue(index.search(new float[DIMENSION], 5).isEmpty(), "零向量查询应返回空");
        index.upsert(1L, randomVector());
        assertTrue(index.search(randomVector(), 0).isEmpty(), "topK<=0 应返回空");
    }

    @Test
    void shouldReturnAllWhenTopKExceedsSize() {
        HnswVectorIndex index = new HnswVectorIndex();
        List<float[]> vectors = seed(7);
        for (int i = 0; i < vectors.size(); i++) {
            index.upsert((long) i, vectors.get(i));
        }
        assertEquals(7, index.search(randomVector(), 50).size());
    }
}
