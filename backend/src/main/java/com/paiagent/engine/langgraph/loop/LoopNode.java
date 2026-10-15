package com.paiagent.engine.langgraph.loop;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 循环节点守卫（迭代5：LoopNode / maxIterations）。
 *
 * <p>LangGraph 的状态图天然支持环（条件边回跳即构成循环），但没有任何机制阻止无限循环。
 * 本类以装饰器方式包裹环入口节点的 {@link AsyncNodeAction}，在状态中按节点累计迭代次数：
 *
 * <ul>
 *   <li>第 N 次进入时计数为 N-1，若 {@code N-1 >= maxIterations} 抛 {@link LoopLimitExceededException}；</li>
 *   <li>否则计数 +1 后放行，继续执行被包裹的真实节点动作。</li>
 * </ul>
 *
 * <p>计数器存放在状态键 {@value #ITERATIONS_KEY} 下的 {@code Map<String, Integer>}（key 为节点 id），
 * 因此多个环可以各自独立计量，互不干扰；状态图每次节点执行都会整体替换 state data，
 * 这里同样以「拷贝后写回」的方式保持不可变风格，与 {@code NodeAdapter} 的做法一致。
 *
 * <p>{@code maxIterations} 取自节点配置 {@code data.maxIterations}，缺省 {@value #DEFAULT_MAX_ITERATIONS}。
 */
@Slf4j
public final class LoopNode {

    /** 缺省最大迭代次数。 */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 状态中存放各循环节点迭代计数的键。 */
    public static final String ITERATIONS_KEY = "__loopIterations__";

    /** 节点配置中读取最大迭代次数的字段名。 */
    public static final String MAX_ITERATIONS_FIELD = "maxIterations";

    private LoopNode() {
    }

    /**
     * 用循环守卫包装节点动作。
     *
     * @param nodeId        节点 id（用作计数器 key）
     * @param maxIterations 最大迭代次数，&lt;=0 时回退为缺省值
     * @param delegate      被包裹的真实节点动作
     */
    public static AsyncNodeAction<AgentState> wrap(String nodeId,
                                                   int maxIterations,
                                                   AsyncNodeAction<AgentState> delegate) {
        int limit = maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
        return state -> {
            Map<String, Object> stateData = state.data();
            int current = readIterations(stateData).getOrDefault(nodeId, 0);
            if (current >= limit) {
                log.warn("循环节点 [{}] 已达到最大迭代次数 {}，强制终止", nodeId, limit);
                throw new LoopLimitExceededException(nodeId, limit);
            }

            Map<String, Integer> counters = new HashMap<>(readIterations(stateData));
            counters.put(nodeId, current + 1);

            Map<String, Object> guarded = new HashMap<>(stateData);
            guarded.put(ITERATIONS_KEY, counters);

            // 节点失败由 NodeAdapter 统一记录为 FAILED，此处保持原有异常语义直接抛出
            return delegate.apply(new AgentState(guarded));
        };
    }

    /** 读取状态中的迭代计数器映射；不存在或类型不符时返回空 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> readIterations(Map<String, Object> stateData) {
        if (stateData == null) {
            return Map.of();
        }
        Object raw = stateData.get(ITERATIONS_KEY);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !(value instanceof Number number)) {
                continue;
            }
            result.put(String.valueOf(key), number.intValue());
        }
        return result;
    }
}
