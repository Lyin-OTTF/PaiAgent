package com.paiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 迭代7（评审报告中危 #7）：全局异常处理映射。
 *
 * <p>修复前无任何 {@code @ControllerAdvice}，异常走 Spring 默认错误页；
 * 此处锁定「统一结构 + 不回显内部细节」两条契约。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("参数非法 → 400 且回显可读原因")
    void badRequestMapsTo400() {
        Result<Void> result = handler.handleBadRequest(new IllegalArgumentException("工作流名称不能为空"));

        assertEquals(400, result.getCode());
        assertEquals("工作流名称不能为空", result.getMessage());
    }

    @Test
    @DisplayName("状态冲突 → 409（如审批已被他人处理）")
    void conflictMapsTo409() {
        Result<Void> result = handler.handleConflict(new IllegalStateException("审批已被其他人处理，请刷新后重试"));

        assertEquals(409, result.getCode());
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("未预期异常 → 500 且不泄露内部细节（SQL/连接串/密钥）")
    void unexpectedMapsTo500WithoutLeakingDetails() {
        Result<Void> result = handler.handleUnexpected(
                new RuntimeException("connection refused: jdbc:mysql://10.0.0.5?password=secret"));

        assertEquals(500, result.getCode());
        assertNotNull(result.getMessage());
        assertFalse(result.getMessage().contains("password"), "响应不得回显内部细节");
        assertFalse(result.getMessage().contains("jdbc"), "响应不得回显内部实现");
    }
}
