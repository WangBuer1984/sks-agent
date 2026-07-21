package com.sks.common;

/** 业务异常：被 {@link GlobalExceptionHandler} 捕获后转换为 {@link ApiResponse#fail(ErrorCode)}。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.msg());
        this.errorCode = errorCode;
    }

    /**
     * 带自定义 message 的业务异常（如携带动态计数）。
     *
     * <p>{@link #errorCode()} 仍返回传入的 {@link ErrorCode}（用于 body 里的 {@code code} 字段），
     * 但 {@link #getMessage()} 返回自定义文案——{@link GlobalExceptionHandler} 用 {@code getMessage()}
     * 作为 body 的 {@code message}，让前端能拿到「有 N 篇稿件引用此卡」这样的动态信息。
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
