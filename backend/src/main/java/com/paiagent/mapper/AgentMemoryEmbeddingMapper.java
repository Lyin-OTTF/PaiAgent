package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.AgentMemoryEmbedding;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 记忆向量 Mapper（迭代2）。
 */
@Mapper
public interface AgentMemoryEmbeddingMapper extends BaseMapper<AgentMemoryEmbedding> {
}
