package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 长期记忆 Mapper（迭代2）。
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {
}
