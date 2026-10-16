package com.paiagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（迭代7 · 报告中危修复）。
 *
 * <p>修复前全仓没有任何 {@code @ControllerAdvice}：未被各 Controller 自行捕获的异常
 * 走 Spring 默认错误响应，结构与统一 {@link Result} 不一致，且可能把堆栈或内部实现
 * 细节（SQL、上游服务报错）回显给调用方。
 *
 * <p>处理策略：
 * <ul>
 *   <li>参数/校验/业务参数异常 → 400，回显可读原因；</li>
 *   <li>状态冲突（审批已被处理、续跑状态不符等） → 409；</li>
 *   <li>其他未预期异常 → 500，只回显通用文案，细节仅入日志。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败（@Valid）与显式的参数非法异常。 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(Exception e) {
        String message = e instanceof MethodArgumentNotValidException binding
                ? binding.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(error -> error.getField() + " " + error.getDefaultMessage())
                        .orElse("请求参数不合法")
                : e.getMessage();
        return Result.error(HttpStatus.BAD_REQUEST.value(), message);
    }

    /** 状态冲突：如审批已被他人处理、断点续跑条件不满足。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(IllegalStateException e) {
        return Result.error(HttpStatus.CONFLICT.value(), e.getMessage());
    }

    /** 兜底：未预期异常只回显通用文案，避免泄露内部实现细节。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未处理的服务端异常", e);
        return Result.error("服务内部错误，请稍后重试");
    }
}
