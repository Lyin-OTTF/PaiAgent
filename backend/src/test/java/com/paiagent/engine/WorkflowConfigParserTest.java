package com.paiagent.engine;

import com.paiagent.engine.model.WorkflowConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConfigParserTest {

    private final WorkflowConfigParser parser = new WorkflowConfigParser();

    @Test
    void parseUsesDataTypeWhenReactFlowNodeTypeIsWorkflow() {
        String flowData = """
                {
                  "nodes": [
                    {
                      "id": "input-1",
                      "type": "workflow",
                      "data": {
                        "type": "input",
                        "label": "输入节点"
                      },
                      "position": {
                        "x": 180,
                        "y": 160
                      }
                    },
                    {
                      "id": "output-1",
                      "type": "workflow",
                      "data": {
                        "type": "output",
                        "label": "输出节点"
                      },
                      "position": {
                        "x": 500,
                        "y": 160
                      }
                    }
                  ],
                  "edges": []
                }
                """;

        WorkflowConfig config = parser.parse(flowData);

        assertEquals("input", config.getNodes().get(0).getType());
        assertEquals("output", config.getNodes().get(1).getType());
    }

    @Test
    void parseKeepsBusinessNodeTypeWhenAlreadySerialized() {
        String flowData = """
                {
                  "nodes": [
                    {
                      "id": "input-1",
                      "type": "input",
                      "data": {
                        "type": "input",
                        "label": "输入节点"
                      }
                    }
                  ],
                  "edges": []
                }
                """;

        WorkflowConfig config = parser.parse(flowData);

        assertEquals("input", config.getNodes().get(0).getType());
    }

    @Test
    @DisplayName("迭代7 A9：空/null 输入返回空配置而非 NPE")
    void toleratesBlankInput() {
        assertTrue(parser.parse(null).getNodes().isEmpty());
        assertTrue(parser.parse("").getNodes().isEmpty());
        assertTrue(parser.parse("   ").getNodes().isEmpty());
        assertTrue(parser.parse("null").getNodes().isEmpty());
    }

    @Test
    @DisplayName("迭代7 A9：非法 JSON 转为带上下文的业务异常")
    void rejectsMalformedJson() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{not json"));
        assertTrue(ex.getMessage().contains("不是合法 JSON"), "错误信息应说明是 JSON 解析失败");
    }

    @Test
    @DisplayName("迭代7 A9：节点/边项不是对象时跳过，不抛 ClassCastException")
    void skipsNonObjectItems() {
        String flowData = """
                {"nodes":["oops",{"id":"n1","type":"input"}],
                 "edges":[123,{"id":"e1","source":"n1","target":"n1"}]}
                """;

        WorkflowConfig config = parser.parse(flowData);

        assertEquals(1, config.getNodes().size());
        assertEquals("n1", config.getNodes().get(0).getId());
        assertEquals(1, config.getEdges().size());
        assertEquals("e1", config.getEdges().get(0).getId());
    }
}
