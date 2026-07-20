package com.sks.common;

/**
 * 业务错误码统一枚举。
 *
 * <p>code 范围约定：
 *
 * <ul>
 *   <li>40xx：业务异常（客户端可见）
 *   <li>50xx：外部服务 / 内容安全异常
 *   <li>4010/4011：C 端 / 管理端未授权
 * </ul>
 */
public enum ErrorCode {
    INSUFFICIENT_BALANCE(4001, "余额不足"),
    SMS_RATE_LIMIT(4002, "短信发送过于频繁，请稍后再试"),
    SMS_CODE_INVALID(4003, "验证码错误"),
    SMS_CODE_LOCKED(4004, "验证码错误次数过多，已锁定 10 分钟"),
    UNAUTHORIZED(4010, "未登录或登录已过期"),
    ADMIN_UNAUTHORIZED(4011, "管理员未登录或无权限"),
    AI_FAILED(5001, "AI 服务异常，请稍后再试"),
    CONTENT_BLOCKED(5002, "内容不符合安全规范，已被拦截");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }
}
