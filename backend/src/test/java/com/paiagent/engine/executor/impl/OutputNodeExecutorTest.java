package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 迭代7：输出节点执行器缺陷修复。
 *
 * <p>锁定三处与 {@code PromptTemplateService} 同源的问题：
 * 带空格占位符不替换、reference 裸参数名不生效、非字符串 value 抛 CCE；
 * 同时确认抽取共用替换逻辑后，LangGraph/DAG 两种引用解析路径仍然正确。
 */
class OutputNodeExecutorTest {

    private final OutputNodeExecutor executor = new OutputNodeExecutor();

    @Test
    @DisplayName("带空格的占位符 {{ name }} 能正确替换（修复点）")
    void replacesPlaceholderWithSpaces() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("output", "上游结果");

        WorkflowNode node = node("""
                {"responseContent":"结果：{{ response }}",
                 "outputParams":[{"name":"response","type":"reference","referenceNode":"llm-1.output"}]}
                """);

        assertEquals("结果：上游结果", executor.execute(node, input).get("output"));
    }

    @Test
    @DisplayName("reference 裸参数名（不带点）也能取值（修复点）")
    void resolvesBareReferenceName() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("output", "裸引用值");

        WorkflowNode node = node("""
                {"responseContent":"{{ r }}",
                 "outputParams":[{"name":"r","type":"reference","referenceNode":"output"}]}
                """);

        assertEquals("裸引用值", executor.execute(node, input).get("output"));
    }

    @Test
    @DisplayName("非字符串 value 不再抛 ClassCastException（修复点）")
    void toleratesNonStringParamValue() {
        WorkflowNode node = node("""
                {"responseContent":"{{ n }}",
                 "outputParams":[{"name":"n","type":"input","value":42}]}
                """);

        assertEquals("42", executor.execute(node, new LinkedHashMap<>()).get("output"));
    }

    @Test
    @DisplayName("参数项类型异常时跳过而非抛异常（防御）")
    void skipsMalformedParamItems() {
        WorkflowNode node = node("""
                {"responseContent":"A{{ x }}B",
                 "outputParams":["oops",{"name":"","type":"input","value":"v"}]}
                """);

        assertEquals("AB", executor.execute(node, new LinkedHashMap<>()).get("output"));
    }

    @Test
    @DisplayName("LangGraph 场景优先从 __nodeOutputs__ 按节点ID精确取引用值")
    void prefersNodeOutputsInLangGraphScenario() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("text", "来自节点A");
        Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
        nodeOutputs.put("nodeA", upstream);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", "当前输入的值");
        input.put("__nodeOutputs__", nodeOutputs);

        WorkflowNode node = node("""
                {"responseContent":"{{ v }}",
                 "outputParams":[{"name":"v","type":"reference","referenceNode":"nodeA.text"}]}
                """);

        assertEquals("来自节点A", executor.execute(node, input).get("output"));
    }

    @Test
    @DisplayName("user_input 引用回落取 input 键")
    void userInputFallsBackToInputKey() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input", "用户原始输入");

        WorkflowNode node = node("""
                {"responseContent":"{{ u }}",
                 "outputParams":[{"name":"u","type":"reference","referenceNode":"user_input"}]}
                """);

        assertEquals("用户原始输入", executor.execute(node, input).get("output"));
    }

    @Test
    @DisplayName("无 responseContent 时透传上游 output")
    void fallsBackToUpstreamOutput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("output", "透传值");

        assertEquals("透传值", executor.execute(node("{}"), input).get("output"));
    }

    private WorkflowNode node(String dataJson) {
        WorkflowNode node = new WorkflowNode();
        node.setId("output-1");
        node.setType("output");
        node.setData(JSON.parseObject(dataJson, Map.class));
        return node;
    }
}
