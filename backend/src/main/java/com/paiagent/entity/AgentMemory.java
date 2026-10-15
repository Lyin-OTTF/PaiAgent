package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 长期记忆表（迭代2 分层扩展）。
 * 向量数据单独存放在 {@code agent_memory_embedding} 表中。
 */
@Data
@TableName("agent_memory")
public class AgentMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分层范围：MemoryScope.code()，如 WORKING/SESSION/USER/TEAM */
    private String scope;

    /** 记忆类型，如 fact / preference / instruction */
    private String memoryType;

    /** 记忆内容（写入时已脱敏） */
    private String content;

    /** 标签，逗号拼接存储 */
    private String tags;

    /** 来源，如节点 ID 或工具名 */
    private String source;

    /** 会话 ID（SESSION/WORKING 层使用） */
    private String sessionId;

    /** 用户 ID（USER 层使用） */
    private String userId;

    /** 租户 ID（TEAM 层使用） */
    private String tenantId;

    /** 重要性权重，默认 0.5 */
    private Double importance;

    /** 过期时间；NULL 表示永不过期 */
    private LocalDateTime expiresAt;

    /** 向量模型名 */
    private String embeddingModel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除：0 未删 / 1 已删 */
    @TableLogic
    private Integer deleted;
}
