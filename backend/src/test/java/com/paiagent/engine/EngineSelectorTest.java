package com.paiagent.engine;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.entity.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 迭代7（评审报告 P2 #11）：引擎选择器此前零测试保护。
 *
 * <p>锁定「显式类型精确匹配、大小写/空白宽容、缺省与未知类型回落 DAG（向后兼容）、
 * 无可用引擎时抛错」四条语义。
 */
class EngineSelectorTest {

    private final WorkflowExecutor dag = stub("dag");
    private final WorkflowExecutor langgraph = stub("langgraph");
    private EngineSelector selector;

    @BeforeEach
    void setUp() {
        selector = new EngineSelector();
        ReflectionTestUtils.setField(selector, "executors", List.of(dag, langgraph));
    }

    @Test
    @DisplayName("engineType=langgraph 选中 LangGraph 执行器")
    void selectsLangGraph() {
        assertSame(langgraph, selector.selectEngine(workflow("langgraph")));
    }

    @Test
    @DisplayName("引擎类型大小写与首尾空白不敏感")
    void normalizesEngineType() {
        assertSame(langgraph, selector.selectEngine(workflow("  LANGGRAPH ")));
    }

    @Test
    @DisplayName("未配置引擎类型时默认 DAG（向后兼容）")
    void defaultsToDagWhenBlank() {
        assertSame(dag, selector.selectEngine(workflow(null)));
        assertSame(dag, selector.selectEngine(workflow("   ")));
    }

    @Test
    @DisplayName("未知引擎类型回落 DAG 而非报错")
    void unknownTypeFallsBackToDag() {
        assertSame(dag, selector.selectEngine(workflow("mystery-engine")));
    }

    @Test
    @DisplayName("无 DAG 执行器且类型未知时抛错（不静默执行不了）")
    void throwsWhenNoUsableExecutor() {
        ReflectionTestUtils.setField(selector, "executors", List.of(langgraph));
        assertThrows(RuntimeException.class, () -> selector.selectEngine(workflow("mystery-engine")));
    }

    private Workflow workflow(String engineType) {
        Workflow workflow = new Workflow();
        workflow.setName("wf");
        workflow.setEngineType(engineType);
        return workflow;
    }

    private WorkflowExecutor stub(String engineType) {
        return new WorkflowExecutor() {
            @Override
            public ExecutionResponse execute(Workflow workflow, String inputData) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ExecutionResponse executeWithCallback(Workflow workflow, String inputData,
                                                         Consumer<ExecutionEvent> eventCallback) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getEngineType() {
                return engineType;
            }
        };
    }
}
