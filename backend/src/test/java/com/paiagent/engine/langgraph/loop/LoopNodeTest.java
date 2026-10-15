package com.paiagent.engine.langgraph.loop;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopNode 单元测试（纯逻辑，无 Spring / 无 DB）。
 *
 * 重点验证：按节点独立计数、达到上限即抛 {@link LoopLimitExceededException}、
 * 计数器随状态向下游传递（NodeAdapter 以整体替换 state data 的方式工作）。
 */
class LoopNodeTest {

    @Test
    void shouldAllowIterationsUpToLimit() {
        AsyncNodeAction<AgentState> delegate = state ->
                CompletableFuture.completedFuture(new HashMap<>(state.data()));
        AsyncNodeAction<AgentState> guarded = LoopNode.wrap("loop-1", 3, delegate);

        AgentState state = new AgentState(new HashMap<>(Map.of("input", "start")));

        // 第 1、2、3 次放行；计数器靠状态向下游流转传递（与 langgraph4j 的回注方式一致）
        for (int i = 1; i <= 3; i++) {
            state = new AgentState(new HashMap<>(guarded.apply(state).join()));
            assertEquals(i, LoopNode.readIterations(state.data()).get("loop-1"));
        }

        // 第 4 次超限
        AgentState exhausted = state;
        LoopLimitExceededException ex = assertThrows(LoopLimitExceededException.class,
                () -> { guarded.apply(exhausted); });
        assertEquals("loop-1", ex.getNodeId());
        assertEquals(3, ex.getMaxIterations());
    }

    @Test
    void shouldCountEachNodeIndependently() {
        AsyncNodeAction<AgentState> delegate = state ->
                CompletableFuture.completedFuture(new HashMap<>(state.data()));
        AsyncNodeAction<AgentState> a = LoopNode.wrap("node-a", 2, delegate);
        AsyncNodeAction<AgentState> b = LoopNode.wrap("node-b", 2, delegate);

        AgentState state = new AgentState(new HashMap<>(Map.of("input", "x")));

        state = new AgentState(new HashMap<>(a.apply(state).join()));
        state = new AgentState(new HashMap<>(b.apply(state).join()));

        // 两个节点的计数互不干扰
        Map<String, Integer> counters = LoopNode.readIterations(state.data());
        assertEquals(1, counters.get("node-a"));
        assertEquals(1, counters.get("node-b"));

        // node-a 已用掉 1 次，还能再跑 1 次；node-b 已用掉 1 次，再跑 1 次后超限
        state = new AgentState(new HashMap<>(a.apply(state).join()));
        state = new AgentState(new HashMap<>(b.apply(state).join()));
        assertEquals(2, LoopNode.readIterations(state.data()).get("node-a"));
        assertEquals(2, LoopNode.readIterations(state.data()).get("node-b"));

        AgentState maxed = state;
        LoopLimitExceededException ex = assertThrows(LoopLimitExceededException.class, () -> { b.apply(maxed); });
        assertEquals("node-b", ex.getNodeId());
        assertThrows(LoopLimitExceededException.class, () -> { a.apply(maxed); });
    }

    @Test
    void shouldCarryCounterIntoDownstreamState() {
        AsyncNodeAction<AgentState> delegate = state ->
                CompletableFuture.completedFuture(new HashMap<>(state.data()));
        AsyncNodeAction<AgentState> guarded = LoopNode.wrap("loop-1", 5, delegate);

        AgentState state = new AgentState(new HashMap<>(Map.of("currentInput", Map.of("k", "v"))));
        AgentState next = new AgentState(new HashMap<>(guarded.apply(state).join()));

        assertEquals(1, LoopNode.readIterations(next.data()).get("loop-1"));
        // 原有状态键不丢
        assertNotNull(next.data().get("currentInput"));
    }

    @Test
    void shouldFallBackToDefaultLimitWhenConfiguredValueInvalid() {
        AsyncNodeAction<AgentState> delegate = state ->
                CompletableFuture.completedFuture(new HashMap<>(state.data()));
        AsyncNodeAction<AgentState> guarded = LoopNode.wrap("loop-1", 0, delegate);

        AgentState state = new AgentState(new HashMap<>(Map.of("input", "x")));
        for (int i = 0; i < LoopNode.DEFAULT_MAX_ITERATIONS; i++) {
            state = new AgentState(new HashMap<>(guarded.apply(state).join()));
        }
        assertEquals(LoopNode.DEFAULT_MAX_ITERATIONS,
                LoopNode.readIterations(state.data()).get("loop-1"));
        AgentState exhausted = state;
        assertThrows(LoopLimitExceededException.class, () -> { guarded.apply(exhausted); });
    }

    @Test
    void shouldReadIterationsDefensively() {
        assertTrue(LoopNode.readIterations(null).isEmpty());
        assertTrue(LoopNode.readIterations(Map.of()).isEmpty());

        Map<String, Object> state = new HashMap<>();
        state.put(LoopNode.ITERATIONS_KEY, "not-a-map");
        assertTrue(LoopNode.readIterations(state).isEmpty());

        Map<String, Object> junk = new HashMap<>();
        junk.put(LoopNode.ITERATIONS_KEY, Map.of("node-a", "not-a-number", "node-b", 7));
        Map<String, Integer> counters = LoopNode.readIterations(junk);
        assertEquals(1, counters.size());
        assertEquals(7, counters.get("node-b"));
    }

    @Test
    void shouldExposeExpectedConstants() {
        assertEquals("__loopIterations__", LoopNode.ITERATIONS_KEY);
        assertEquals("maxIterations", LoopNode.MAX_ITERATIONS_FIELD);
        assertEquals(10, LoopNode.DEFAULT_MAX_ITERATIONS);
    }
}
