package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.llm.PromptTemplateService;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出节点执行器。
 *
 * <p>迭代7 修复三处与 {@code PromptTemplateService} 同源的缺陷：
 * <ul>
 *   <li>带空格的占位符 {@code {{ name }}} 此前永远替换不到
 *       （变量名已 trim，却用去空格后的 {@code {{name}}} 去 replace）；</li>
 *   <li>{@code reference} 只支持 {@code nodeId.param} 带点写法，裸参数名被静默忽略；</li>
 *   <li>{@code (String) param.get("value")} 对非字符串值抛 ClassCastException。</li>
 * </ul>
 * 模板替换统一委托 {@link PromptTemplateService#replaceVariables}，消除重复实现——
 * 同一缺陷此前在多处各自复制，修一处不能覆盖全部。
 */
@Slf4j
@Component
public class OutputNodeExecutor implements NodeExecutor {

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> output = new HashMap<>();

        Map<String, Object> nodeData = node.getData();
        if (nodeData == null) {
            output.put("output", fallbackOutput(input));
            return output;
        }

        Object rawTemplate = nodeData.get("responseContent");
        String responseContent = rawContent(rawTemplate);
        if (responseContent == null || responseContent.isEmpty()) {
            output.put("output", fallbackOutput(input));
            return output;
        }

        log.info("输出节点配置 - responseContent: {}", responseContent);
        log.info("输出节点配置 - outputParams: {}", nodeData.get("outputParams"));
        log.info("输出节点输入数据: {}", input);

        Map<String, String> paramValues = buildParamValues(nodeData.get("outputParams"), input);
        log.info("参数值映射: {}", paramValues);

        String result = PromptTemplateService.replaceVariables(responseContent, paramValues);
        log.info("输出节点最终结果: {}", result);
        output.put("output", result);
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "output";
    }

    /** 无前驱输出时的兜底：优先透传上游 output，其次 input。 */
    private Object fallbackOutput(Map<String, Object> input) {
        Object upstream = input.get("output");
        return upstream != null ? upstream : input.get("input");
    }

    /** responseContent 可能是任意类型（配置脏数据），统一转字符串，避免强转 CCE。 */
    private String rawContent(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 构建「参数名 → 取值」映射。
     * 逐项做类型防御：历史实现直接对 {@code name}/{@code value} 强转 String，
     * 非字符串配置会抛 ClassCastException。
     */
    private Map<String, String> buildParamValues(Object rawParams, Map<String, Object> input) {
        Map<String, String> paramValues = new HashMap<>();
        if (!(rawParams instanceof List<?> params)) {
            return paramValues;
        }

        for (Object item : params) {
            if (!(item instanceof Map<?, ?> rawParam)) {
                continue;
            }
            String paramName = asText(rawParam.get("name"));
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            paramName = paramName.trim();
            String paramType = asText(rawParam.get("type"));

            if ("input".equals(paramType)) {
                Object value = rawParam.get("value");
                if (value != null) {
                    paramValues.put(paramName, String.valueOf(value));
                }
            } else if ("reference".equals(paramType)) {
                Object refValue = resolveReference(asText(rawParam.get("referenceNode")), input);
                if (refValue != null) {
                    paramValues.put(paramName, String.valueOf(refValue));
                }
            }
        }
        return paramValues;
    }

    /**
     * 解析引用值（迭代7：兼容裸参数名）。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>{@code nodeId.param} 写法且输入含 {@code __nodeOutputs__}（LangGraph 场景）
     *       → 按节点 ID + 参数名精确取；</li>
     *   <li>回落为「当前输入按参数名取」（DAG 场景：前驱输出即当前输入）；</li>
     *   <li>{@code user_input} 兼容映射到 {@code input} 键。</li>
     * </ol>
     */
    private Object resolveReference(String reference, Map<String, Object> input) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String[] parts = reference.trim().split("\\.");
        String refParamName = parts[parts.length - 1];
        String refNodeId = parts.length > 1 ? parts[0] : null;
        if (refParamName.isBlank()) {
            return null;
        }

        Object refValue = null;
        if (refNodeId != null && input.get("__nodeOutputs__") instanceof Map<?, ?> nodeOutputs
                && nodeOutputs.get(refNodeId) instanceof Map<?, ?> nodeOutput) {
            refValue = nodeOutput.get(refParamName);
            log.info("从 nodeOutputs 中找到引用 {}.{} 的值: {}", refNodeId, refParamName, refValue);
        }
        if (refValue == null) {
            refValue = input.get(refParamName);
        }
        if (refValue == null && "user_input".equals(refParamName)) {
            refValue = input.get("input");
        }
        log.info("引用参数 {} 的值: {}", refParamName, refValue);
        return refValue;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
