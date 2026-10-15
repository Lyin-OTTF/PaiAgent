package com.paiagent.engine.analysis;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 轻量中英文混合分词器：中文按 2-4 gram 切分，英文按词切分，过滤停用词。
 * <p>分词规则与 KnowledgeBaseService 原有文本相关性保持一致，收敛为共享组件：
 * 迭代3 RAG（BM25 词频 / 覆盖度）与后续记忆检索复用，避免各处重复实现。
 */
public final class ChineseAnalyzer {

    private static final ChineseAnalyzer INSTANCE = new ChineseAnalyzer();

    private ChineseAnalyzer() {
    }

    public static ChineseAnalyzer shared() {
        return INSTANCE;
    }

    /**
     * 归一化：小写、非中英数字字符折叠为空格、压缩连续空白。
     */
    public String normalize(String text) {
        return text == null ? "" : text.toLowerCase()
                .replaceAll("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 查询侧检索词：中文 2-4 gram（短词整体）、英文词（长度>=2）、去停用词、保序去重。
     */
    public Set<String> terms(String text) {
        return new LinkedHashSet<>(termsList(text));
    }

    /**
     * 文档侧词频统计：与 {@link #terms(String)} 同一套切分规则，返回 term -> tf（保留重复计数）。
     * <p>BM25 的词频饱和依赖真实 tf，因此必须走允许重复的有序列表，不能基于去重集合。
     */
    public Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        for (String term : termsList(text)) {
            frequencies.merge(term, 1, Integer::sum);
        }
        return frequencies;
    }

    /**
     * 切分规则的有序列表（允许重复）：去重版供查询侧，重复保留版供文档侧词频。
     */
    private java.util.List<String> termsList(String text) {
        java.util.List<String> terms = new java.util.ArrayList<>();
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return terms;
        }
        for (String part : normalized.split("\\s+")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (containsHan(part)) {
                addChineseTerms(terms, part);
            } else if (part.length() >= 2 && !isStopTerm(part)) {
                terms.add(part);
            }
        }
        return terms;
    }

    /**
     * 归一化后长度（BM25 的文档长度代理，避免中英混合下字符数失真）。
     */
    public int normalizedLength(String text) {
        return normalize(text).length();
    }

    private void addChineseTerms(java.util.List<String> terms, String text) {
        if (text.length() <= 4 && !isStopTerm(text)) {
            terms.add(text);
            return;
        }
        for (int size = 2; size <= 4; size++) {
            if (text.length() < size) {
                continue;
            }
            for (int i = 0; i <= text.length() - size; i++) {
                String term = text.substring(i, i + size);
                if (!isStopTerm(term)) {
                    terms.add(term);
                }
            }
        }
    }

    private boolean containsHan(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isStopTerm(String term) {
        return switch (term) {
            case "应该", "怎么", "什么", "如何", "可以", "需要", "一个", "这个", "那个", "是否", "以及",
                 "the", "and", "for", "with", "what", "how" -> true;
            default -> false;
        };
    }
}
