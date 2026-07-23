package com.sks.common;

/**
 * 短信发送 seam（§3 联调首检「阿里云短信」）。
 *
 * <p>实现 {@link AliyunSmsClient}：条件降级（key 空 → stub 不抛）+ 真 SendSms（失败抛
 * {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})）。{@link com.sks.auth.AuthService#sendCode}
 * 发验证码、{@link QuotaWatchJob#sendAlert} 发告警，均经此 seam。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-07-23-aliyun-sms-wiring-design.md}。
 */
public interface SmsClient {

    /** 发验证码。key 未配置时为 no-op（stub）；configured 但发送失败时抛 SMS_SEND_FAILED。 */
    void sendVerificationCode(String phone, String code);

    /** 发告警短信给站长。失败可抛 SMS_SEND_FAILED，由 {@link QuotaWatchJob#sweep} try/catch 兜底。 */
    void sendAlert(String phone, String reason);
}
