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

    /**
     * 挂起上下文快照（迭代6 HITL 恢复）。
     * ReAct 节点转入审批时，由执行器把「续跑所需的全部状态」序列化存入：
     * goalPrompt / input / trace / step / maxSteps / toolName / toolInput / tenantId / allowedLevel 等。
     * 修复前只存 500 字符摘要，审批通过后无法还原参数补执行，闭环断裂在此。
     */
    private String suspendContext;

    /**
     * 补执行状态（迭代6）：SUCCEEDED / FAILED / SKIPPED / null（未触发补执行）。
     * SKIPPED 覆盖「无挂起上下文」的历史审批（仅审计闭环的旧行为兼容）。
     */
    private String resumeStatus;

    /** 补执行失败原因（resumeStatus=FAILED 时非空） */
    private String resumeError;

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
