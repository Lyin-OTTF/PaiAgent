package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.ApprovalRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人工审批记录 Mapper。
 */
@Mapper
public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecord> {
}
