package com.sks.common;

/**
 * 统一返回体。
 *
 * <pre>
 * 成功：{ "code": 0, "message": "ok", "data": ... }
 * 失败：{ "code": 4001, "message": "余额不足", "data": null }
 * </pre>
 */
public record ApiResponse<T>(int code, String message, T data) {

    private static final int OK_CODE = 0;
    private static final String OK_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK_CODE, OK_MESSAGE, data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.msg(), null);
    }
}
