package com.paiagent.dto;

import lombok.Data;

/**
 * 审批决策请求（通过/拒绝时携带审批人与意见）。
 */
@Data
public class ApprovalDecisionRequest {

    /** 审批人标识 */
    private String approver;

    /** 审批意见 */
    private String note;
}
