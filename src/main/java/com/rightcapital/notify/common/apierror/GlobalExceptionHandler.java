package com.rightcapital.notify.common.apierror;

import com.rightcapital.notify.common.statemachine.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理（T1.5）：所有 4xx 返回结构化错误码，调用方按 code 分支处理。
 * 500 只回 traceId，细节留在日志 —— 不把内部信息泄漏给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldMessage(fe))
                .collect(Collectors.joining("; "));
        return badRequest(ErrorCode.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(IllegalStateTransitionException ex) {
        log.warn("非法状态迁移: {} -> {}", ex.getFrom(), ex.getTo());
        return ResponseEntity.status(ErrorCode.STATE_TRANSITION_ILLEGAL.httpStatus())
                .body(ApiError.of(ErrorCode.STATE_TRANSITION_ILLEGAL, ex.getMessage(), TraceIds.current()));
    }

    /** 接入侧可预期拒绝（URL 策略 / 限流 / 背压）：带 Retry-After（如适用）。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), TraceIds.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String traceId = TraceIds.current();
        // 细节只进日志；响应体不含异常信息
        log.error("未处理异常 traceId={}", traceId, ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "服务内部错误，请凭 traceId 联系维护方", traceId));
    }

    private static ResponseEntity<ApiError> badRequest(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, message, TraceIds.current()));
    }

    private static String fieldMessage(FieldError fe) {
        return "%s: %s".formatted(fe.getField(), fe.getDefaultMessage());
    }
}
