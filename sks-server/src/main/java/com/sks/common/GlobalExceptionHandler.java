package com.sks.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把业务异常与校验异常统一封装为 {@link ApiResponse}。
 *
 * <ul>
 *   <li>{@link BizException} → {@link ApiResponse#fail(ErrorCode)}，HTTP 200（错误码在 body）
 *   <li>校验异常（参数不合法）→ code 4000，HTTP 400
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：返回体里携带具体 ErrorCode。
     *
     * <p>用 {@link BizException#getMessage()} 作为 body 的 message——对默认构造的 BizException
     * 来说 == {@code errorCode.msg()}（无回归），对 {@link BizException#BizException(ErrorCode, String)}
     * 的自定义消息变体则透传动态文案（如「有 3 篇稿件引用此卡」）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        return ResponseEntity.ok(new ApiResponse<>(ex.errorCode().code(), ex.getMessage(), null));
    }

    /** @RequestBody 校验失败：聚合字段错误，返回 4000。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(4000, message, null));
    }

    /** @RequestParam / @PathVariable 校验失败：返回 4000。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(4000, ex.getMessage(), null));
    }
}
