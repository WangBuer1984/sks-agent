package com.sks.common;

/** 业务异常：被 {@link GlobalExceptionHandler} 捕获后转换为 {@link ApiResponse#fail(ErrorCode)}。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.msg());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
