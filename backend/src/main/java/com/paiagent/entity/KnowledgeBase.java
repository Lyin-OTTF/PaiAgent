package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /**
     * 资源归属（迭代7 · 评审报告 A2）：登录用户名。
     * 列表/详情/写入均按此隔离，防止跨用户访问；系统内部创建的资源归 "system"。
     */
    private String owner;

    private Long configId;

    private String embeddingModel;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private String status;

    private Integer documentCount;

    private Integer chunkCount;

    private Long charCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
