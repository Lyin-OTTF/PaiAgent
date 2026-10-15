package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.engine.langgraph.checkpoint.LangGraphCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LangGraph 检查点 Mapper。
 */
@Mapper
public interface LangGraphCheckpointMapper extends BaseMapper<LangGraphCheckpoint> {

    /**
     * 查询某线程下的全部检查点（按写入顺序，最早的在最前）。
     */
    @Select("SELECT * FROM langgraph_checkpoint WHERE thread_id = #{threadId} ORDER BY seq")
    List<LangGraphCheckpoint> selectByThreadId(@Param("threadId") String threadId);

    /**
     * 查询某线程下最新写入的一条检查点。
     *
     * <p>必须按自增序号 {@code seq} 倒序而不是按 {@code id}：检查点 ID 是随机 UUID，
     * 按 ID 排序无法反映写入先后。
     */
    @Select("SELECT * FROM langgraph_checkpoint WHERE thread_id = #{threadId} ORDER BY seq DESC LIMIT 1")
    LangGraphCheckpoint selectLatestByThreadId(@Param("threadId") String threadId);

    /**
     * 保留策略：删除某线程下不在最新 N 条以内的旧检查点（迭代5 遗留治理）。
     *
     * <p>用派生表定位截断点：MySQL 不允许 DELETE 的子查询直接引用同表，必须再包一层；
     * MySQL 的 OFFSET 只接受整数占位符（不接受 {@code ? - 1} 这类表达式），故「保留 N 条」
     * 的算术放在 Java 侧：{@code cutoffOffset = N - 1}，即按 seq 倒序跳过这么多条后定位截断点，
     * 删除比它更旧的行，恰好保留 N 条。线程内检查点不足 N 条时子查询为空、截断点为 NULL，
     * {@code seq < NULL} 恒不成立，一行不删。
     *
     * <p>恢复语义不受影响：{@link #selectLatestByThreadId} 只取最新一条，
     * 而最新一条必然落在保留窗口内。
     *
     * @param cutoffOffset 保留条数减一（0 = 只保留最新一条）
     * @return 实际删除行数
     */
    @Delete("""
            DELETE FROM langgraph_checkpoint
            WHERE thread_id = #{threadId}
              AND seq < (SELECT cutoff FROM (
                         SELECT seq AS cutoff FROM langgraph_checkpoint
                         WHERE thread_id = #{threadId}
                         ORDER BY seq DESC LIMIT 1 OFFSET #{cutoffOffset}
                     ) AS boundary)
            """)
    int deleteOldByThreadId(@Param("threadId") String threadId, @Param("cutoffOffset") int cutoffOffset);

    /**
     * 保留策略：删除创建时间早于截止时间的检查点（跨线程的时间窗清理）。
     *
     * @return 实际删除行数
     */
    @Delete("DELETE FROM langgraph_checkpoint WHERE created_at < #{cutoff}")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
