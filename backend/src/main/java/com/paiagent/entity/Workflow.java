package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工作流实体类
 */
@Data
@TableName("workflow")
public class Workflow {
    
    /**
     * 工作流主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 工作流名称
     */
    private String name;
    
    /**
     * 工作流描述
     */
    private String description;
    
    /**
     * 工作流配置数据(节点和连线) - JSON 格式
     */
    private String flowData;
    
    /**
     * 工作流引擎类型: dag(默认), langgraph
     */
    private String engineType;
    
    /**
     * 资源归属（迭代7 · 评审报告 A1）：登录用户名。
     * 列表/详情/执行入口均按此隔离，防止跨用户访问；系统内部创建的资源归 "system"。
     */
    private String owner;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    
    /**
     * 逻辑删除标识
     */
    @TableLogic
    private Integer deleted;
}
