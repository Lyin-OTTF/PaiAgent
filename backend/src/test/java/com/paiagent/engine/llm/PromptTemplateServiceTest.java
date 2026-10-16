package com.paiagent.engine.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代6：#32 PromptTemplateService 缺陷修复测试。
 *
 * <p>覆盖两处修复：
 * <ul>
 *   <li>{@code {{ var }}} 这类带空格的占位符此前永远匹配不到（替换键用了去空格后的
 *       {@code {{var}}}），修复后单遍替换可识别空格；</li>
 *   <li>{@code reference} 只写裸参数名（不含 "."）时此前被静默忽略，修复后可正常取值。</li>
 * </ul>
 */
class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    private Map<String, Object> inputParam(String name, String value) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("name", name);
        param.put("type", "input");
        param.put("value", value);
        return param;
    }

    private Map<String, Object> referenceParam(String name, String referenceNode) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("name", name);
        param.put("type", "reference");
        param.put("referenceNode", referenceNode);
        return param;
    }

    @Test
    @DisplayName("带空格的占位符 {{ var }} 能被替换（修复点）")
    void replacesPlaceholderWithSpaces() {
        List<Map<String, Object>> params = List.of(inputParam("topic", "AI"));
        assertEquals("请写一篇关于 AI 的文章",
                service.processTemplate("请写一篇关于 {{ topic }} 的文章", params, Map.of()));
    }

    @Test
    @DisplayName("不带空格的占位符 {{var}} 依旧可替换（回归）")
    void replacesPlaceholderWithoutSpaces() {
        List<Map<String, Object>> params = List.of(inputParam("topic", "AI"));
        assertEquals("关于AI", service.processTemplate("关于{{topic}}", params, Map.of()));
    }

    @Test
    @DisplayName("同一变量多处出现（含空格/无空格混用）全部替换")
    void replacesAllOccurrencesMixedSpacing() {
        List<Map<String, Object>> params = List.of(inputParam("x", "V"));
        assertEquals("V-V-V", service.processTemplate("{{x}}-{{ x }}-{{x}}", params, Map.of()));
    }

    @Test
    @DisplayName("未定义变量替换为空串")
    void unknownVariableBecomesEmpty() {
        assertEquals("A--B", service.processTemplate("A-{{ missing }}-B", List.of(), Map.of()));
    }

    @Test
    @DisplayName("reference 带点 nodeId.param 取值（回归）")
    void referenceWithDot() {
        List<Map<String, Object>> params = List.of(referenceParam("ctx", "llm-1.output"));
        assertEquals("上一步结果",
                service.processTemplate("{{ctx}}", params, Map.of("output", "上一步结果")));
    }

    @Test
    @DisplayName("reference 不带点裸参数名也能取值（修复点）")
    void referenceWithoutDot() {
        List<Map<String, Object>> params = List.of(referenceParam("ctx", "output"));
        assertEquals("上一步结果",
                service.processTemplate("{{ctx}}", params, Map.of("output", "上一步结果")));
    }

    @Test
    @DisplayName("reference=user_input 回落取 input（回归）")
    void referenceUserInputFallsBackToInput() {
        List<Map<String, Object>> params = List.of(referenceParam("ctx", "user_input"));
        assertEquals("用户原始输入",
                service.processTemplate("{{ctx}}", params, Map.of("input", "用户原始输入")));
    }

    @Test
    @DisplayName("参数值含 $ 与反斜杠时按字面量写入，不被当作替换分组")
    void replacementValueWithDollarIsLiteral() {
        List<Map<String, Object>> params = List.of(inputParam("code", "$1 \\n end"));
        assertEquals("值: $1 \\n end", service.processTemplate("值: {{code}}", params, Map.of()));
    }

    @Test
    @DisplayName("参数值本身含 {{...}} 不会被二次替换")
    void replacementValueNotReScanned() {
        List<Map<String, Object>> params = List.of(
                inputParam("a", "{{b}}"),
                inputParam("b", "B"));
        assertEquals("{{b}}", service.processTemplate("{{a}}", params, Map.of()));
    }

    @Test
    @DisplayName("参数名带空格时仍能与占位符对齐（键统一 trim）")
    void paramNameWithSpacesAligns() {
        List<Map<String, Object>> params = List.of(inputParam("  topic  ", "AI"));
        assertEquals("AI", service.processTemplate("{{ topic }}", params, Map.of()));
    }

    @Test
    @DisplayName("模板为 null 时返回空串（边界）")
    void nullTemplateReturnsEmpty() {
        assertTrue(service.processTemplate(null, List.of(), Map.of()).isEmpty());
    }

    @Test
    @DisplayName("无占位符模板原样返回")
    void noPlaceholderReturnsAsIs() {
        assertFalse(service.processTemplate("纯文本", List.of(), Map.of()).contains("{{"));
    }
}
