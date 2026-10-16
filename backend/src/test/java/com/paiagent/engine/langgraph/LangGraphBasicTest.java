package com.paiagent.engine.langgraph;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangGraph4j 基础功能测试
 * 验证 LangGraph4j 依赖是否正确加载
 *
 * <p>迭代7：原先的 3 个 WorkflowState 用例随该遗留模型一并删除——
 * WorkflowState 在生产代码中零调用（LangGraph 引擎统一走 Map 状态），
 * 其测试只是自测自，属死代码清理范围。
 */
@SpringBootTest(properties = {
        "paiagent.auth.jwt-secret=test-jwt-secret-key-for-spring-context-123456",
        "minio.endpoint=http://localhost:9000",
        "minio.accessKey=minioadmin",
        "minio.secretKey=minioadmin",
        "minio.bucketName=paiagent",
        "minio.publicUrl=http://localhost:9000",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.datasource.password=${MYSQL_PASSWORD:1234}"
})
class LangGraphBasicTest {
    
    @Test
    void testLangGraphDependency() throws Exception {
        // 验证可以创建 StateGraph
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        
        // 添加一个简单节点
        graph.addNode("test", state -> 
            java.util.concurrent.CompletableFuture.completedFuture(
                Map.of("message", "Hello LangGraph")
            )
        );
        
        // 设置入口点
        graph.addEdge(StateGraph.START, "test");
        graph.addEdge("test", StateGraph.END);
        
        // 编译图
        var compiled = graph.compile();
        
        // 验证图编译成功
        assertNotNull(compiled);
        
        System.out.println("✅ LangGraph4j 依赖加载成功！");
    }
}
