package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 记忆向量表，与 {@link AgentMemory} 通过 memory_id 关联。
 * embedding 以 JSON 数组字符串形式存储。
 */
@Data
@TableName("agent_memory_embedding")
public class AgentMemoryEmbedding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 agent_memory.id */
    private Long memoryId;

    /** 向量模型名 */
    private String model;

    /** 向量维度 */
    private Integer dimension;

    /** 向量数据（JSON 数组字符串） */
    private String embedding;

    private LocalDateTime createdAt;
}
