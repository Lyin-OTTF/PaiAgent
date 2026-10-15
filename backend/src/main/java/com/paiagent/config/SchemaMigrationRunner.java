package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies small idempotent schema upgrades for local/dev databases that were created before new features.
 */
@Slf4j
@Component
@Order(0)
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createTableIfMissing("knowledge_base", """
                CREATE TABLE knowledge_base (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识库 ID',
                    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
                    config_id BIGINT DEFAULT NULL COMMENT 'Agent Plan 全局配置 ID',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    chunk_size INT DEFAULT 800 COMMENT '分片长度',
                    chunk_overlap INT DEFAULT 100 COMMENT '分片重叠长度',
                    status VARCHAR(30) DEFAULT 'DRAFT' COMMENT '状态',
                    document_count INT DEFAULT 0 COMMENT '文档数',
                    chunk_count INT DEFAULT 0 COMMENT '分片数',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表'
                """);
        createTableIfMissing("knowledge_document", """
                CREATE TABLE knowledge_document (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识文档 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    title VARCHAR(255) DEFAULT NULL COMMENT '文档标题',
                    source_type VARCHAR(30) DEFAULT 'TEXT' COMMENT '来源类型',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    file_name VARCHAR(255) DEFAULT NULL COMMENT '文件名',
                    raw_text MEDIUMTEXT COMMENT '原始文本',
                    tags VARCHAR(500) DEFAULT NULL COMMENT '标签',
                    status VARCHAR(30) DEFAULT 'IMPORTED' COMMENT '状态',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    error_message TEXT COMMENT '错误信息',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_document_base (knowledge_base_id),
                    INDEX idx_knowledge_document_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表'
                """);
        createTableIfMissing("knowledge_index_task", """
                CREATE TABLE knowledge_index_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '索引任务 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT NOT NULL COMMENT '文档 ID',
                    status VARCHAR(30) DEFAULT 'RUNNING' COMMENT '状态',
                    progress INT DEFAULT 0 COMMENT '进度',
                    total_chunks INT DEFAULT 0 COMMENT '总分片数',
                    finished_chunks INT DEFAULT 0 COMMENT '完成分片数',
                    error_message TEXT COMMENT '错误信息',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_index_task_base (knowledge_base_id),
                    INDEX idx_knowledge_index_task_document (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库索引任务表'
                """);
        createTableIfMissing("knowledge_chunk", """
                CREATE TABLE knowledge_chunk (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识片段主键 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID',
                    chunk_index INT DEFAULT 0 COMMENT '分片序号',
                    title VARCHAR(255) DEFAULT NULL COMMENT '标题',
                    content TEXT NOT NULL COMMENT '片段内容',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    tags JSON COMMENT '标签',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    embedding JSON COMMENT '向量数据',
                    status VARCHAR(30) DEFAULT 'READY' COMMENT '状态',
                    char_count INT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_id (knowledge_base_id),
                    INDEX idx_knowledge_document_id (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库片段表'
                """);
        // 迭代3：knowledge_chunk.content 的 FULLTEXT(ngram) 索引，BM25 词法召回路依赖。
        // 老库建表时无此索引，需幂等补建；DDL 失败（如 MariaDB 无 ngram parser）只记日志不阻断启动，
        // 检索侧自动降级为「仅向量路 / 旧全表扫描」。
        addFulltextIndexIfMissing("knowledge_chunk", "ft_knowledge_chunk_content",
                "ALTER TABLE knowledge_chunk ADD FULLTEXT INDEX ft_knowledge_chunk_content (content) WITH PARSER ngram");
        createTableIfMissing("mcp_tool_config", """
                CREATE TABLE mcp_tool_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'MCP 工具配置 ID',
                    name VARCHAR(100) NOT NULL COMMENT '名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
                    tool_type VARCHAR(50) DEFAULT 'custom' COMMENT '工具类型',
                    tool_name VARCHAR(100) NOT NULL COMMENT '暴露给 Agent 的工具名',
                    transport VARCHAR(30) DEFAULT 'stdio' COMMENT '传输方式',
                    command VARCHAR(500) NOT NULL COMMENT '启动命令',
                    args JSON COMMENT '启动参数',
                    env JSON COMMENT '环境变量',
                    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
                    preset TINYINT DEFAULT 0 COMMENT '是否预设',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_mcp_tool_name (tool_name),
                    INDEX idx_mcp_tool_type (tool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP工具配置表'
                """);
        createTableIfMissing("execution_snapshot", """
                CREATE TABLE execution_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    flow_id BIGINT NOT NULL COMMENT '工作流 ID',
                    node_id VARCHAR(100) NOT NULL COMMENT '节点 ID',
                    node_type VARCHAR(50) NOT NULL COMMENT '节点类型',
                    node_name VARCHAR(255) DEFAULT NULL COMMENT '节点名称',
                    status VARCHAR(50) NOT NULL COMMENT '节点状态',
                    input_data JSON COMMENT '节点输入数据',
                    output_data JSON COMMENT '节点输出数据',
                    error_message TEXT COMMENT '错误信息',
                    started_at TIMESTAMP NULL COMMENT '开始执行时间',
                    completed_at TIMESTAMP NULL COMMENT '完成时间',
                    duration INT COMMENT '执行耗时(毫秒)',
                    retry_count INT DEFAULT 0 COMMENT '重试次数',
                    execution_order INT DEFAULT 0 COMMENT '执行顺序',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_execution_id (execution_id),
                    INDEX idx_flow_id (flow_id),
                    INDEX idx_node_id (node_id),
                    INDEX idx_status (status),
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行快照表'
                """);
        createTableIfMissing("execution_variable", """
                CREATE TABLE execution_variable (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '变量主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    variable_name VARCHAR(100) NOT NULL COMMENT '变量名',
                    variable_type VARCHAR(50) DEFAULT 'STRING' COMMENT '变量类型',
                    variable_value TEXT COMMENT '变量值',
                    is_modified TINYINT DEFAULT 0 COMMENT '是否被修改',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    UNIQUE KEY uk_execution_variable (execution_id, variable_name),
                    INDEX idx_execution_id (execution_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行变量表'
                """);
        addColumnIfMissing("llm_global_config", "embedding_model",
                "ALTER TABLE llm_global_config ADD COLUMN embedding_model VARCHAR(100) DEFAULT NULL COMMENT '默认向量模型' AFTER tts_model");
        addColumnIfMissing("llm_global_config", "image_model",
                "ALTER TABLE llm_global_config ADD COLUMN image_model VARCHAR(100) DEFAULT NULL COMMENT '默认图片生成模型' AFTER embedding_model");
        addColumnIfMissing("llm_global_config", "video_model",
                "ALTER TABLE llm_global_config ADD COLUMN video_model VARCHAR(100) DEFAULT NULL COMMENT '默认视频生成模型' AFTER image_model");
        addColumnIfMissing("llm_global_config", "memory_enabled",
                "ALTER TABLE llm_global_config ADD COLUMN memory_enabled TINYINT DEFAULT 0 COMMENT '是否启用Agent Plan记忆能力' AFTER video_model");
        addColumnIfMissing("knowledge_chunk", "document_id",
                "ALTER TABLE knowledge_chunk ADD COLUMN document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID' AFTER knowledge_base_id");
        addColumnIfMissing("knowledge_chunk", "chunk_index",
                "ALTER TABLE knowledge_chunk ADD COLUMN chunk_index INT DEFAULT 0 COMMENT '分片序号' AFTER document_id");
        addColumnIfMissing("knowledge_chunk", "status",
                "ALTER TABLE knowledge_chunk ADD COLUMN status VARCHAR(30) DEFAULT 'READY' COMMENT '状态' AFTER embedding");
        addColumnIfMissing("knowledge_chunk", "char_count",
                "ALTER TABLE knowledge_chunk ADD COLUMN char_count INT DEFAULT 0 COMMENT '字符数' AFTER status");

        createTableIfMissing("action_ledger", """
                CREATE TABLE action_ledger (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审计主键 ID',
                    execution_id BIGINT DEFAULT NULL COMMENT '执行记录 ID',
                    workflow_id BIGINT DEFAULT NULL COMMENT '工作流 ID',
                    node_id VARCHAR(100) DEFAULT NULL COMMENT '节点 ID',
                    tool_name VARCHAR(100) NOT NULL COMMENT '工具名',
                    caller VARCHAR(255) DEFAULT NULL COMMENT '调用方标识',
                    tenant_id VARCHAR(100) DEFAULT NULL COMMENT '租户 ID',
                    input_summary TEXT COMMENT '入参摘要',
                    autonomy_level VARCHAR(30) DEFAULT NULL COMMENT '工具所需自治等级',
                    write_flag TINYINT DEFAULT 0 COMMENT '是否写操作(0/1)',
                    action_status VARCHAR(30) NOT NULL COMMENT '动作状态(EXECUTED/DENIED/PENDING_APPROVAL/APPROVED)',
                    result_summary TEXT COMMENT '结果摘要',
                    error_message TEXT COMMENT '错误信息',
                    approval_id BIGINT DEFAULT NULL COMMENT '关联审批记录 ID',
                    idempotency_key VARCHAR(255) DEFAULT NULL COMMENT '幂等键',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_action_ledger_tool (tool_name),
                    INDEX idx_action_ledger_status (action_status),
                    INDEX idx_action_ledger_approval (approval_id),
                    INDEX idx_action_ledger_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具调用审计表'
                """);

        createTableIfMissing("approval_record", """
                CREATE TABLE approval_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审批主键 ID',
                    execution_id BIGINT DEFAULT NULL COMMENT '执行记录 ID',
                    workflow_id BIGINT DEFAULT NULL COMMENT '工作流 ID',
                    node_id VARCHAR(100) DEFAULT NULL COMMENT '节点 ID',
                    tool_name VARCHAR(100) NOT NULL COMMENT '工具名',
                    caller VARCHAR(255) DEFAULT NULL COMMENT '调用方标识',
                    tenant_id VARCHAR(100) DEFAULT NULL COMMENT '租户 ID',
                    input_summary TEXT COMMENT '入参摘要',
                    requested_level VARCHAR(30) DEFAULT NULL COMMENT '请求的自治等级',
                    status VARCHAR(30) NOT NULL COMMENT '审批状态(PENDING/APPROVED/REJECTED)',
                    approver VARCHAR(255) DEFAULT NULL COMMENT '审批人',
                    decision_note TEXT COMMENT '审批意见',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    decided_at TIMESTAMP NULL COMMENT '决策时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_approval_status (status),
                    INDEX idx_approval_tool (tool_name),
                    INDEX idx_approval_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工审批记录表'
                """);

        // 迭代2：扩展 agent_memory 表，支持分层记忆（WORKING/SESSION/USER/TEAM）+ TTL + 脱敏来源
        addColumnIfMissing("agent_memory", "session_id",
                "ALTER TABLE agent_memory ADD COLUMN session_id VARCHAR(100) DEFAULT NULL COMMENT '会话 ID（SESSION/WORKING 层）' AFTER source");
        addColumnIfMissing("agent_memory", "user_id",
                "ALTER TABLE agent_memory ADD COLUMN user_id VARCHAR(100) DEFAULT NULL COMMENT '用户 ID（USER 层）' AFTER session_id");
        addColumnIfMissing("agent_memory", "tenant_id",
                "ALTER TABLE agent_memory ADD COLUMN tenant_id VARCHAR(100) DEFAULT NULL COMMENT '租户 ID（TEAM 层）' AFTER user_id");
        addColumnIfMissing("agent_memory", "importance",
                "ALTER TABLE agent_memory ADD COLUMN importance DOUBLE DEFAULT 0.5 COMMENT '重要性权重（用于检索排序）' AFTER tenant_id");
        addColumnIfMissing("agent_memory", "expires_at",
                "ALTER TABLE agent_memory ADD COLUMN expires_at TIMESTAMP NULL COMMENT '过期时间（TTL 清理，NULL 表示不过期）' AFTER importance");
        addColumnIfMissing("agent_memory", "embedding_model",
                "ALTER TABLE agent_memory ADD COLUMN embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型' AFTER expires_at");

        // 迭代5：LangGraph 检查点表，支撑 LangGraph 引擎的断点恢复
        createTableIfMissing("langgraph_checkpoint", """
                CREATE TABLE langgraph_checkpoint (
                    id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '检查点 ID（langgraph4j 生成的 UUID）',
                    thread_id VARCHAR(128) NOT NULL COMMENT '线程 ID（= 执行记录 ID）',
                    node_id VARCHAR(128) DEFAULT NULL COMMENT '刚执行完的节点 ID',
                    next_node_id VARCHAR(128) DEFAULT NULL COMMENT '下一个待执行节点 ID',
                    state_json MEDIUMTEXT COMMENT '图状态 JSON',
                    seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增序号，标识检查点写入顺序（恢复时取最大）',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    UNIQUE KEY uk_langgraph_checkpoint_seq (seq),
                    INDEX idx_langgraph_checkpoint_thread (thread_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LangGraph 检查点表'
                """);
        // 迭代5补丁：检查点 ID 是随机 UUID，按 ID 排序无法定位最新写入，补一个自增序号列
        addColumnIfMissing("langgraph_checkpoint", "seq",
                "ALTER TABLE langgraph_checkpoint ADD COLUMN seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增序号，标识检查点写入顺序', ADD UNIQUE KEY uk_langgraph_checkpoint_seq (seq)");
    }

    private void createTableIfMissing(String tableName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, tableName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: created {}", tableName);
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: added {}.{}", tableName, columnName);
    }

    /**
     * 幂等补建索引：information_schema.STATISTICS 命中即跳过。
     * DDL 异常只告警不抛出——索引是检索优化而非正确性依赖，环境不支持时检索自动降级。
     */
    private void addFulltextIndexIfMissing(String tableName, String indexName, String ddl) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                      AND INDEX_NAME = ?
                    """, Integer.class, tableName, indexName);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute(ddl);
            log.info("Schema migrated: created fulltext index {} on {}", indexName, tableName);
        } catch (Exception e) {
            log.warn("Schema migrate skipped (fulltext index {} on {}): {}", indexName, tableName, e.getMessage());
        }
    }
}
