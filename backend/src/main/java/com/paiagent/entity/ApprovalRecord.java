package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工审批记录（Human-in-the-Loop）。
 * 当工具所需自治等级超过运行时允许等级，或显式要求审批时，创建 PENDING 记录；
 * 审批通过后由调用方决定如何恢复执行。
 */
@Data
@TableName("approval_record")
public class ApprovalRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行记录 ID */
    private Long executionId;

    /** 工作流 ID */
    private Long workflowId;

    /** 节点 ID */
    private String nodeId;

    /** 工具名 */
    private String toolName;

    /** 调用方标识 */
    private String caller;

    /** 租户 ID */
    private String tenantId;

    /** 入参摘要 */
    private String inputSummary;

    /** 请求的自治等级 */
    private String requestedLevel;

    /** 审批状态：PENDING / APPROVED / REJECTED */
    private String status;

    /** 审批人 */
    private String approver;

    /** 审批意见 */
    private String decisionNote;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 决策时间 */
    private LocalDateTime decidedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
