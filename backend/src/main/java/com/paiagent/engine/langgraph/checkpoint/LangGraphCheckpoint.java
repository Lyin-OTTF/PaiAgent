package com.paiagent.engine.langgraph.checkpoint;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LangGraph 检查点实体（迭代5：LangGraph 断点恢复）。
 *
 * <p>对应 langgraph4j 的 {@code org.bsc.langgraph4j.checkpoint.Checkpoint}：
 * 一份「执行到某节点后的完整状态 + 下一个待执行节点」的快照。
 * 状态以 JSON 存储，避免依赖 Java 序列化（节点输出里可能出现不可序列化对象）。
 */
@Data
@TableName("langgraph_checkpoint")
public class LangGraphCheckpoint {

    /** 检查点 ID（langgraph4j 生成的 UUID，主键）。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 线程 ID = 执行记录 ID，同一次执行的所有检查点归属同一线程。 */
    private String threadId;

    /** 生成该检查点时刚执行完的节点 ID（START 表示入口检查点）。 */
    private String nodeId;

    /** 下一个待执行的节点 ID；END 表示图已执行到出口。 */
    private String nextNodeId;

    /** 序列化后的图状态 JSON。 */
    private String stateJson;

    /**
     * 自增序号（数据库侧 AUTO_INCREMENT），标识检查点的写入顺序。
     *
     * <p>为什么需要它：langgraph4j 生成的检查点 ID 是随机 UUID，
     * 按 ID 排序无法反映「谁是最新写入的」。恢复语义要求取线程内
     * <b>最后写入</b>的检查点（对齐 MemorySaver 的 push/peek 语义），
     * 因此必须依赖单调递增的写入序号。
     */
    private Long seq;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;
}
