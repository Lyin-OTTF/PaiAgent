package com.paiagent.engine.langgraph.loop;

/**
 * 循环节点超过最大迭代次数时抛出。
 *
 * <p>LangGraph4j 自带的全局迭代上限（默认 25）在触顶时只是<b>静默结束</b>图执行
 * （{@code Data.done()}），既不报错也不留痕，容易把「死循环被截断」误判成「正常跑完」。
 * 本异常由 {@link LoopNode} 在节点级计数超限时抛出，经由 {@code NodeAdapter} 的
 * 异常分支写入 {@code status=FAILED} 与 {@code errorMessage}，让失败可观测。
 */
public class LoopLimitExceededException extends RuntimeException {

    private final String nodeId;
    private final int maxIterations;

    public LoopLimitExceededException(String nodeId, int maxIterations) {
        super(String.format("节点 [%s] 循环次数超过最大迭代次数 %d，已强制终止", nodeId, maxIterations));
        this.nodeId = nodeId;
        this.maxIterations = maxIterations;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getMaxIterations() {
        return maxIterations;
    }
}
