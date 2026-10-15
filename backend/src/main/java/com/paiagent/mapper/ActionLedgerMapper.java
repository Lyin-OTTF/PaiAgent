package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.ActionLedger;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具调用审计 Mapper。
 */
@Mapper
public interface ActionLedgerMapper extends BaseMapper<ActionLedger> {
}
