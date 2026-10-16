package com.paiagent.engine.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion 纯函数：score(d) = Σ_routes 1 / (k + rank_r(d))，rank 从 1 起。
 * <p>只依赖「排名」不依赖各路原始分数量级，因此 BM25 / cosine / 任意分制可直接相加。
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * @param routes 各路的排序结果（已按该路分数降序）
     * @param k      平滑常数，越大路间差异越被压缩；60 为论文常用值
     * @return 元素 -&gt; 融合分；同一路内若出现重复元素会各自按名次累加
     *         （迭代7：修正注释与实现不一致的描述——实现走 {@code merge(.., Double::sum)}，
     *         并非「只按首次出现计」；当前各路输入均为去重 id 列表，该分支不会触发）
     */
    public static <T> Map<T, Double> fuse(List<? extends List<? extends T>> routes, int k) {
        Map<T, Double> scores = new LinkedHashMap<>();
        if (routes == null || routes.isEmpty() || k <= 0) {
            return scores;
        }
        for (List<? extends T> route : routes) {
            if (route == null) {
                continue;
            }
            int rank = 1;
            for (T element : route) {
                if (element == null) {
                    continue;
                }
                scores.merge(element, 1.0 / (k + rank), Double::sum);
                rank++;
            }
        }
        return scores;
    }

    public static <T> Map<T, Double> fuse(List<? extends List<? extends T>> routes) {
        return fuse(routes, DEFAULT_K);
    }
}
