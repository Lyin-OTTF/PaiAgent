package com.paiagent.engine.rag;

import com.paiagent.engine.analysis.ChineseAnalyzer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 迭代3 RAG 装配：注册配置属性与重排器。
 * <p>EmbeddingFunction 的生产实现（Agent Plan 客户端适配）注册在 service 层，
 * 避免引擎层反向依赖 service；测试可注入确定性实现。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {

    @Bean
    public Reranker reranker(RagProperties properties) {
        return new Reranker(
                ChineseAnalyzer.shared(),
                properties.rrfWeight(),
                properties.coverageWeight(),
                properties.titleWeight());
    }
}
