package com.paiagent.engine.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void shouldFuseRanksAcrossRoutes() {
        Map<String, Double> fused = RrfFusion.fuse(List.of(
                List.of("a", "b", "c"),
                List.of("b", "c", "a")
        ), 60);

        // 双路第一：a=1/61+1/63, b=1/62+1/61
        assertEquals(1.0 / 61 + 1.0 / 63, fused.get("a"), 1e-9);
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get("b"), 1e-9);
        assertEquals(1.0 / 63 + 1.0 / 62, fused.get("c"), 1e-9);
        assertTrue(fused.get("a") > fused.get("c"), "两路均靠前者应领先");
    }

    @Test
    void shouldGiveBonusForMultiRouteHits() {
        Map<String, Double> fused = RrfFusion.fuse(List.of(
                List.of("x", "y"),
                List.of("y", "x")
        ), 60);
        // 对称排名下两元素同分：x=1/62+1/61, y=1/61+1/62
        assertEquals(fused.get("x"), fused.get("y"), 1e-9);
        // 双路命中分数应高于任何单路首名（1/61）
        assertTrue(fused.get("x") > 1.0 / 61, "两路均命中应优于单路第一名");
    }

    @Test
    void shouldTolerateNullOrEmptyRoutes() {
        assertTrue(RrfFusion.fuse(null, 60).isEmpty());
        assertTrue(RrfFusion.fuse(List.of(), 60).isEmpty());
        // Arrays.asList 允许 null 元素，List.of 不允许
        Map<String, Double> withNullRoute = RrfFusion.fuse(java.util.Arrays.asList(List.of("a"), null), 60);
        assertTrue(withNullRoute.containsKey("a"));
        assertTrue(RrfFusion.fuse(List.of(List.of("a")), 0).isEmpty());
    }

    @Test
    void shouldUseDefaultK() {
        Map<String, Double> fused = RrfFusion.fuse(List.of(List.of("a", "b")));
        assertEquals(1.0 / (RrfFusion.DEFAULT_K + 1), fused.get("a"), 1e-9);
        assertEquals(1.0 / (RrfFusion.DEFAULT_K + 2), fused.get("b"), 1e-9);
    }
}
