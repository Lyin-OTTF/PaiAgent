package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses workflow JSON without relying on FastJSON bean cache for nested classes.
 */
@Component
public class WorkflowConfigParser {
    private static final String REACT_FLOW_NODE_TYPE = "workflow";

    public WorkflowConfig parse(String flowData) {
        WorkflowConfig config = new WorkflowConfig();
        // 迭代7 A9：空输入此前会让 parseObject 返回 null，随后 root.getJSONArray 抛 NPE
        if (flowData == null || flowData.isBlank()) {
            config.setNodes(new ArrayList<>());
            config.setEdges(new ArrayList<>());
            return config;
        }

        JSONObject root;
        try {
            root = JSON.parseObject(flowData);
        } catch (Exception e) {
            // 迭代7 A9：非法 JSON 不再以未捕获异常冒出，统一转成带上下文的业务异常，
            // 便于上层（Controller/全局异常处理）返回可读错误
            throw new IllegalArgumentException("工作流配置不是合法 JSON: " + e.getMessage(), e);
        }
        if (root == null) {
            config.setNodes(new ArrayList<>());
            config.setEdges(new ArrayList<>());
            return config;
        }

        config.setNodes(parseNodes(root.getJSONArray("nodes")));
        config.setEdges(parseEdges(root.getJSONArray("edges")));
        return config;
    }

    private List<WorkflowNode> parseNodes(JSONArray array) {
        List<WorkflowNode> nodes = new ArrayList<>();
        if (array == null) {
            return nodes;
        }

        for (Object item : array) {
            // 迭代7 A9：节点项非对象时跳过，避免 (JSONObject) 强转抛 ClassCastException
            if (!(item instanceof JSONObject json)) {
                continue;
            }
            JSONObject dataJson = json.getJSONObject("data");
            WorkflowNode node = new WorkflowNode();
            node.setId(json.getString("id"));
            node.setType(resolveNodeType(json, dataJson));
            node.setData(parseMap(dataJson));

            JSONObject positionJson = json.getJSONObject("position");
            if (positionJson != null) {
                WorkflowNode.Position position = new WorkflowNode.Position();
                position.setX(positionJson.getDouble("x"));
                position.setY(positionJson.getDouble("y"));
                node.setPosition(position);
            }

            nodes.add(node);
        }
        return nodes;
    }

    private String resolveNodeType(JSONObject nodeJson, JSONObject dataJson) {
        String nodeType = nodeJson.getString("type");
        String dataType = dataJson != null ? dataJson.getString("type") : null;
        if ((nodeType == null || nodeType.isBlank() || REACT_FLOW_NODE_TYPE.equals(nodeType))
                && dataType != null && !dataType.isBlank()) {
            return dataType;
        }
        return nodeType;
    }

    private List<WorkflowEdge> parseEdges(JSONArray array) {
        List<WorkflowEdge> edges = new ArrayList<>();
        if (array == null) {
            return edges;
        }

        for (Object item : array) {
            // 迭代7 A9：边项非对象时跳过，避免 (JSONObject) 强转抛 ClassCastException
            if (!(item instanceof JSONObject json)) {
                continue;
            }
            WorkflowEdge edge = new WorkflowEdge();
            edge.setId(json.getString("id"));
            edge.setSource(json.getString("source"));
            edge.setTarget(json.getString("target"));
            edge.setSourceHandle(json.getString("sourceHandle"));
            edge.setTargetHandle(json.getString("targetHandle"));
            edges.add(edge);
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(JSONObject json) {
        if (json == null) {
            return Map.of();
        }
        return JSON.parseObject(json.toJSONString(), Map.class);
    }
}
