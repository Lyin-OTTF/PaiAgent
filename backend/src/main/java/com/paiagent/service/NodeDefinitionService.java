package com.paiagent.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.NodeDefinition;
import com.paiagent.mapper.NodeDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点定义服务
 */
@Service
public class NodeDefinitionService extends ServiceImpl<NodeDefinitionMapper, NodeDefinition> {
    private static final List<String> HIDDEN_STANDALONE_AGENT_NODE_TYPES = List.of(
            "react_agent",
            "web_search",
            "web_fetch",
            "vision_analyze",
            "memory_write",
            "memory_retrieve",
            "knowledge_upsert",
            "knowledge_retrieve"
    );
    
    /**
     * 查询所有节点定义
     */
    public List<NodeDefinition> listAllNodeDefinitions() {
        Map<String, NodeDefinition> nodeDefinitionMap = new LinkedHashMap<>();
        this.list().forEach(node -> nodeDefinitionMap.put(node.getNodeType(), node));

        nodeDefinitionMap.putIfAbsent("llm", createGenericLlmNodeDefinition());
        // 迭代7：原先还注入了 memory_write / memory_retrieve / knowledge_upsert / knowledge_retrieve，
        // 但这四个类型同时在 HIDDEN_STANDALONE_AGENT_NODE_TYPES 中，注入后会被下方过滤立即剔除，
        // 属不可达死代码（库中亦无种子行），故删除；这些节点仅作为 Agent 模式工具在 LLM 节点内配置。
        nodeDefinitionMap.putIfAbsent("image_generate", createNodeDefinition(
                "image_generate", "图片生成", "TOOL", "IMG",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"imageUrl\":{\"type\":\"string\"},\"imageUrls\":{\"type\":\"array\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"metadata\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"referenceImageUrl\":{\"type\":\"string\"},\"size\":{\"type\":\"string\",\"default\":\"1024x1024\"},\"style\":{\"type\":\"string\"},\"count\":{\"type\":\"number\",\"default\":1},\"negativePrompt\":{\"type\":\"string\"},\"steps\":{\"type\":\"number\"},\"cfgScale\":{\"type\":\"number\"},\"seed\":{\"type\":\"number\"},\"textMode\":{\"type\":\"boolean\",\"default\":true}}}"
        ));
        nodeDefinitionMap.putIfAbsent("video_generate", createNodeDefinition(
                "video_generate", "视频生成", "TOOL", "VID",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"videoUrl\":{\"type\":\"string\"},\"coverUrl\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"metadata\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"referenceImageUrl\":{\"type\":\"string\"},\"duration\":{\"type\":\"number\",\"default\":5},\"resolution\":{\"type\":\"string\"},\"ratio\":{\"type\":\"string\",\"default\":\"adaptive\"},\"cameraMotion\":{\"type\":\"string\"}}}"
        ));
        return nodeDefinitionMap.values().stream()
                .filter(node -> !HIDDEN_STANDALONE_AGENT_NODE_TYPES.contains(node.getNodeType()))
                .filter(node -> !"LLM".equals(node.getCategory())
                        || "llm".equals(node.getNodeType()))
                .toList();
    }
    
    /**
     * 根据节点类型查询
     */
    public NodeDefinition getByNodeType(String nodeType) {
        if ("llm".equals(nodeType)) {
            return createGenericLlmNodeDefinition();
        }
        if ("react_agent".equals(nodeType)) {
            return createReActAgentNodeDefinition();
        }

        return this.lambdaQuery()
                .eq(NodeDefinition::getNodeType, nodeType)
                .one();
    }

    private NodeDefinition createGenericLlmNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("llm");
        nodeDefinition.setDisplayName("大模型");
        nodeDefinition.setCategory("LLM");
        nodeDefinition.setIcon("🤖");
        nodeDefinition.setInputSchema("{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\": \"object\", \"properties\": {\"output\": {\"type\": \"string\"}, \"tokens\": {\"type\": \"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\": \"object\", \"properties\": {\"provider\": {\"type\": \"string\"}, \"configId\": {\"type\": \"number\"}, \"apiKey\": {\"type\": \"string\"}, \"model\": {\"type\": \"string\"}, \"prompt\": {\"type\": \"string\"}, \"temperature\": {\"type\": \"number\", \"default\": 0.7}, \"maxTokens\": {\"type\": \"number\", \"default\": 1000}, \"agentStrategy\": {\"type\": \"string\", \"default\": \"none\"}, \"maxSteps\": {\"type\": \"number\", \"default\": 5}, \"tools\": {\"type\": \"array\"}, \"memoryEnabled\": {\"type\": \"boolean\", \"default\": false}, \"memoryTopK\": {\"type\": \"number\", \"default\": 5}, \"knowledgeBaseId\": {\"type\": \"string\"}, \"knowledgeTopK\": {\"type\": \"number\", \"default\": 5}, \"knowledgeScoreThreshold\": {\"type\": \"number\", \"default\": 0.2}}}");
        return nodeDefinition;
    }

    private NodeDefinition createReActAgentNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("react_agent");
        nodeDefinition.setDisplayName("ReAct Agent");
        nodeDefinition.setCategory("LLM");
        nodeDefinition.setIcon("RA");
        nodeDefinition.setInputSchema("{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\": \"object\", \"properties\": {\"output\": {\"type\": \"string\"}, \"finalAnswer\": {\"type\": \"string\"}, \"toolTrace\": {\"type\": \"array\"}, \"steps\": {\"type\": \"number\"}, \"tokens\": {\"type\": \"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\": \"object\", \"properties\": {\"provider\": {\"type\": \"string\"}, \"configId\": {\"type\": \"number\"}, \"apiKey\": {\"type\": \"string\"}, \"model\": {\"type\": \"string\"}, \"prompt\": {\"type\": \"string\"}, \"temperature\": {\"type\": \"number\", \"default\": 0.7}, \"maxSteps\": {\"type\": \"number\", \"default\": 5}, \"tools\": {\"type\": \"array\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createNodeDefinition(String nodeType, String displayName, String category,
                                                String icon, String inputSchema, String outputSchema,
                                                String configSchema) {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType(nodeType);
        nodeDefinition.setDisplayName(displayName);
        nodeDefinition.setCategory(category);
        nodeDefinition.setIcon(icon);
        nodeDefinition.setInputSchema(inputSchema);
        nodeDefinition.setOutputSchema(outputSchema);
        nodeDefinition.setConfigSchema(configSchema);
        return nodeDefinition;
    }
}
