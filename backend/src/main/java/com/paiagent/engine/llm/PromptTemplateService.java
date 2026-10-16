package com.paiagent.engine.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt模板处理服务
 * 负责处理模板变量替换和参数映射
 */
@Slf4j
@Service
public class PromptTemplateService {
    
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.*?)\\}\\}");
    
    /**
     * 处理prompt模板，替换其中的变量
     *
     * @param promptTemplate 原始模板，包含{{variable}}格式的变量
     * @param inputParams    输入参数配置列表
     * @param runtimeInput   运行时输入数据（来自上游节点输出）
     * @return 替换后的最终prompt
     */
    public String processTemplate(String promptTemplate, 
                                  List<Map<String, Object>> inputParams, 
                                  Map<String, Object> runtimeInput) {
        if (promptTemplate == null) {
            return "";
        }
        
        // 构建参数值映射
        Map<String, String> paramValues = buildParamValues(inputParams, runtimeInput);
        log.debug("参数值映射: {}", paramValues);
        
        // 替换模板变量
        return replaceVariables(promptTemplate, paramValues);
    }
    
    /**
     * 构建参数值映射
     * 支持两种参数类型：
     * - input: 静态值，直接从配置中获取
     * - reference: 引用值，从上游节点输出中获取
     */
    private Map<String, String> buildParamValues(List<Map<String, Object>> inputParams, 
                                                  Map<String, Object> runtimeInput) {
        Map<String, String> paramValues = new HashMap<>();
        
        if (inputParams == null) {
            return paramValues;
        }
        
        for (Map<String, Object> param : inputParams) {
            if (param == null) {
                continue;
            }
            String paramName = asString(param.get("name"));
            String paramType = asString(param.get("type"));
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            // 与 replaceTemplateVariables 的取键方式保持一致（都 trim），
            // 否则参数名带空格时模板占位符取不到值
            paramName = paramName.trim();
            
            if ("input".equals(paramType)) {
                // 静态输入值
                Object value = param.get("value");
                if (value != null) {
                    paramValues.put(paramName, String.valueOf(value));
                }
            } else if ("reference".equals(paramType)) {
                // 引用上游节点输出：兼容 "nodeId.param" 与裸 "param" 两种写法。
                // 修复前只处理含 "." 的写法，reference 直接写参数名时会被静默忽略。
                String refParamName = extractReferenceParamName(asString(param.get("referenceNode")));
                if (refParamName == null) {
                    continue;
                }
                
                Object refValue = runtimeInput == null ? null : runtimeInput.get(refParamName);
                // 兼容处理：user_input 可能存储为 input
                if (refValue == null && "user_input".equals(refParamName) && runtimeInput != null) {
                    refValue = runtimeInput.get("input");
                }
                
                if (refValue != null) {
                    paramValues.put(paramName, String.valueOf(refValue));
                }
            }
        }
        
        return paramValues;
    }
    
    /**
     * 从引用标识中取末段参数名：既支持 {@code "nodeId.param"}，也支持裸 {@code "param"}。
     * 无法解析时返回 null（调用方跳过该参数）。
     */
    private String extractReferenceParamName(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String trimmed = reference.trim();
        int dot = trimmed.lastIndexOf('.');
        String name = dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
        return name.isBlank() ? null : name;
    }
    
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
    
    /**
     * 替换模板中的 {{variable}} 变量（迭代7：抽出为静态工具方法，供多个执行器复用）。
     *
     * <p>用 {@link Matcher#appendReplacement} 单遍替换：
     * <ul>
     *   <li>变量名取 {@code group(1).trim()}，因此 {@code {{ var }}} 这类带空格的
     *       占位符也能被识别（修复前用去空格后的 {@code {{var}}} 去 replace，
     *       带空格的占位符永远匹配不到）；</li>
     *   <li>替换整段 {@code group(0)}，不做二次扫描，参数值里若恰好含
     *       {@code {{...}}} 也不会被重复替换；</li>
     *   <li>用 {@link Matcher#quoteReplacement} 转义，参数值含 {@code $} / {@code \}
     *       时不会被当成替换分组引用而损坏内容。</li>
     * </ul>
     *
     * <p>抽为 {@code public static} 的目的：输出节点等执行器此前各自复制了一份替换逻辑，
     * 于是同一个「带空格占位符」缺陷在多处重复出现；统一委托本方法后修一处即全局生效。
     */
    public static String replaceVariables(String template, Map<String, String> paramValues) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, String> values = paramValues != null ? paramValues : Map.of();
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String paramName = matcher.group(1).trim();
            String paramValue = values.get(paramName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(paramValue == null ? "" : paramValue));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
}
