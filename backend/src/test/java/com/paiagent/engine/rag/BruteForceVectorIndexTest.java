package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruteForceVectorIndexTest {

    @Test
    void shouldReturnTopKInDescendingOrder() {
        BruteForceVectorIndex index = new BruteForceVectorIndex();
        Random random = new Random(7L);
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            float[] vector = new float[32];
            for (int j = 0; j < 32; j++) {
                vector[j] = random.nextFloat() - 0.5f;
            }
            vectors.add(vector);
            index.upsert((long) i, vector);
        }

        List<VectorIndex.ScoredVector> hits = index.search(vectors.get(50), 5);

        assertEquals(5, hits.size());
        assertEquals(50L, hits.getFirst().id());
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(), "应按分数降序");
        }
    }

    @Test
    void shouldHandleRemoveAndEdgeCases() {
        BruteForceVectorIndex index = new BruteForceVectorIndex();
        index.upsert(1L, new float[]{1, 0, 0});
        index.upsert(2L, new float[]{0, 1, 0});
        index.remove(1L);
        assertEquals(1, index.size());
        assertTrue(index.search(new float[]{1, 0, 0}, 5).stream().noneMatch(v -> v.id() == 1L));
        assertTrue(index.search(new float[3], 5).isEmpty(), "零向量查询返回空");
        assertTrue(index.search(new float[]{1, 0, 0}, 0).isEmpty());
    }
}
