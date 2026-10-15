package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计表（ActionLedger）。
 * 记录每一次 Agent 工具调用的调用方、入参摘要、自治等级、执行结果/审批与补偿信息，保证操作链路可追溯。
 */
@Data
@TableName("action_ledger")
public class ActionLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行记录 ID（来自工作流执行，可能为 null） */
    private Long executionId;

    /** 工作流 ID */
    private Long workflowId;

    /** 节点 ID */
    private String nodeId;

    /** 工具名 */
    private String toolName;

    /** 调用方标识 */
    private String caller;

    /** 租户 ID（跨租户校验用） */
    private String tenantId;

    /** 入参摘要（脱敏后的 JSON/文本） */
    private String inputSummary;

    /** 工具所需自治等级 */
    private String autonomyLevel;

    /** 是否写操作（0=否，1=是） */
    private Integer writeFlag;

    /** 动作状态：EXECUTED / DENIED / PENDING_APPROVAL / APPROVED */
    private String actionStatus;

    /** 结果摘要 */
    private String resultSummary;

    /** 错误信息 */
    private String errorMessage;

    /** 关联审批记录 ID（如经审批） */
    private Long approvalId;

    /** 幂等键 */
    private String idempotencyKey;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
