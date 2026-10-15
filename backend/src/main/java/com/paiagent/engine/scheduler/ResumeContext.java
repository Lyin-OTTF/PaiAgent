package com.paiagent.engine.scheduler;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * 续跑上下文：从指定节点恢复执行时，携带已恢复的历史输出与跳过集合
 */
@Data
@Builder
public class ResumeContext {

    /** 续跑起始节点 ID（为空表示全新执行） */
    private String startNodeId;

    /** 已恢复的前置节点输出（节点 ID -> 输出），这些节点不会重新执行 */
    private Map<String, Map<String, Object>> restoredNodeOutputs;

    /** 条件分支未选中、需跳过的节点集合 */
    private Set<String> skippedNodes;

    /** 初始输入（无前驱输出时的回退输入，也是无任何节点执行时的最终输出） */
    private Map<String, Object> initialInput;

    public static ResumeContext empty() {
        return builder().build();
    }
}
