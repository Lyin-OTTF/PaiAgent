package com.paiagent.engine.langgraph.checkpoint;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.mapper.LangGraphCheckpointMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LangGraph 检查点持久化（迭代5：LangGraph 断点恢复）。
 *
 * <p>实现 langgraph4j 的 {@link BaseCheckpointSaver}，把每个节点执行后的图状态落到
 * {@code langgraph_checkpoint} 表，从而让 LangGraph 引擎具备与 DAG 引擎对等的断点续跑能力。
 *
 * <p><b>为什么用 JSON 而不是 Java 序列化</b>：图状态里的节点输出可能包含不可序列化对象
 * （如 HTTP 客户端、输入流），而 langgraph4j 默认的 {@code ObjectStreamStateSerializer} 依赖 Java 序列化；
 * JSON 只要求「可转字符串」，且与项目其余部分（fastjson2）一致。
 *
 * <p><b>线程模型</b>：{@code threadId = 执行记录 ID}。一次执行对应一个线程，线程内按写入顺序累积检查点，
 * 恢复时取该线程最新一条。因此同一 executionId 重复执行会自动接上旧状态（恢复语义），
 * 而每次全新执行都会拿到新的 executionId，互不干扰。
 *
 * <p><b>保留策略</b>（迭代5 遗留治理）：检查点表若无限累积会随执行次数无界增长，故 put 成功后
 * 按线程保留最新 {@code retainPerThread} 条（默认 50，可配 {@code paiagent.langgraph.checkpoint-retain-per-thread}），
 * 更早的旧检查点删除；恢复只依赖线程内最新一条，必然落在保留窗口内，语义不受影响。
 * 跨线程的时间窗清理由 {@link LangGraphCheckpointRetentionTask} 定时完成。
 *
 * <p><b>容错</b>：检查点是恢复辅助能力，不应因为写库失败而让用户工作流失败，
 * 故 {@link #put} 内部吞异常并打 ERROR 日志（恢复能力降级，执行不中断）；
 * 保留策略清理失败同样只打 WARN，不影响执行与恢复。
 */
@Slf4j
@Component
public class LangGraphCheckpointSaver implements BaseCheckpointSaver {

    /** 未显式指定 threadId 时使用的兜底线程名。 */
    private static final String FALLBACK_THREAD_ID = "__default__";

    /** langgraph4j 的结束节点标识。 */
    private static final String END_NODE = "__END__";

    /** 单线程默认保留的检查点条数（覆盖绝大多数工作流的全量检查点，恢复只依赖最新一条）。 */
    public static final int DEFAULT_RETAIN_PER_THREAD = 50;

    private final LangGraphCheckpointMapper mapper;

    /** 单线程保留窗口：put 成功后删除该线程更早的检查点，防止执行次数累积导致表无限增长。 */
    private final int retainPerThread;

    public LangGraphCheckpointSaver(
            LangGraphCheckpointMapper mapper,
            @Value("${paiagent.langgraph.checkpoint-retain-per-thread:50}") int retainPerThread) {
        this.mapper = mapper;
        this.retainPerThread = Math.max(1, retainPerThread);
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        if (checkpoint == null) {
            return config;
        }
        String threadId = config.threadId().orElse(FALLBACK_THREAD_ID);
        try {
            LangGraphCheckpoint row = new LangGraphCheckpoint();
            row.setId(checkpoint.getId());
            row.setThreadId(threadId);
            row.setNodeId(checkpoint.getNodeId());
            row.setNextNodeId(checkpoint.getNextNodeId());
            row.setStateJson(serializeState(checkpoint.getState()));

            LangGraphCheckpoint existing = mapper.selectById(checkpoint.getId());
            if (existing == null) {
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
            trimOldCheckpoints(threadId);
        } catch (Exception e) {
            // 检查点写入失败不阻断工作流执行，仅记录错误（恢复能力降级）
            log.error("写入 LangGraph 检查点失败: threadId={}, nodeId={}", threadId, checkpoint.getNodeId(), e);
        }
        return config;
    }

    /**
     * 保留策略：删除该线程不在最新 {@code retainPerThread} 条内的旧检查点。
     * 清理失败同样不影响工作流（只打 WARN），与 put 主路径的容错等级一致。
     *
     * <p>Mapper 的 {@code cutoffOffset} = 保留条数 - 1：MySQL 的 OFFSET 只接受
     * 整数占位符，「N - 1」的算术必须在 Java 侧完成。
     */
    private void trimOldCheckpoints(String threadId) {
        try {
            mapper.deleteOldByThreadId(threadId, retainPerThread - 1);
        } catch (Exception e) {
            log.warn("清理 LangGraph 旧检查点失败（不影响执行与恢复）: threadId={}", threadId, e);
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        try {
            LangGraphCheckpoint row = config.checkPointId()
                    .map(mapper::selectById)
                    .orElseGet(() -> mapper.selectLatestByThreadId(threadId(config)));
            return toCheckpoint(row);
        } catch (Exception e) {
            log.error("读取 LangGraph 检查点失败: threadId={}", threadId(config), e);
            return Optional.empty();
        }
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        try {
            List<LangGraphCheckpoint> rows = mapper.selectByThreadId(threadId(config));
            List<Checkpoint> result = new ArrayList<>(rows.size());
            for (LangGraphCheckpoint row : rows) {
                toCheckpoint(row).ifPresent(result::add);
            }
            return result;
        } catch (Exception e) {
            log.error("查询 LangGraph 检查点历史失败: threadId={}", threadId(config), e);
            return List.of();
        }
    }

    /**
     * 查询某线程最新检查点的原始信息（供引擎判断是否已跑到终点）。
     *
     * @return 无检查点时返回 {@code null}
     */
    public LangGraphCheckpoint findLatest(String threadId) {
        return mapper.selectLatestByThreadId(threadId);
    }

    private static Optional<Checkpoint> toCheckpoint(LangGraphCheckpoint row) {
        if (row == null || row.getId() == null) {
            return Optional.empty();
        }
        // nextNodeId 为空的检查点没有可恢复的下一步（执行已收尾），视为不存在
        if (row.getNextNodeId() == null || row.getNextNodeId().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> state = deserializeState(row.getStateJson());
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(Checkpoint.builder()
                .id(row.getId())
                .nodeId(row.getNodeId() == null ? "" : row.getNodeId())
                .nextNodeId(row.getNextNodeId())
                .state(state)
                .build());
    }

    private static String threadId(RunnableConfig config) {
        return config.threadId().orElse(FALLBACK_THREAD_ID);
    }

    /** 状态 -&gt; JSON；序列化失败时返回空对象，避免把 null 写进 state_json。 */
    private static String serializeState(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.toJSONString(state);
        } catch (Exception e) {
            log.warn("序列化 LangGraph 状态失败，退化为空状态: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * JSON -&gt; 状态。统一归一成 {@link LinkedHashMap} / {@link ArrayList}，
     * 使恢复后的状态与全新执行产生的状态在类型上一致（消费方按 Map/List 强转）。
     */
    private static Map<String, Object> deserializeState(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof Map)) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> normalized = normalizeMap((Map<?, ?>) parsed);
            return normalized == null ? new LinkedHashMap<>() : normalized;
        } catch (Exception e) {
            log.warn("反序列化 LangGraph 状态失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return result;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof JSONObject jsonObject) {
            return normalizeMap(jsonObject);
        }
        if (value instanceof JSONArray jsonArray) {
            List<Object> list = new ArrayList<>(jsonArray.size());
            for (Object item : jsonArray) {
                list.add(normalizeValue(item));
            }
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(normalizeValue(item));
            }
            return result;
        }
        return value;
    }
}
