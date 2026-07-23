package com.sks.common;

/**
 * 短信发送 seam（DYPNS 短信认证，spec §3.2）。
 *
 * <p>实现 {@link AliyunSmsAuthClient}：条件降级（key/template 空 → stub 不抛）+ 真 SendSmsVerifyCode
 * （字面码模式，失败抛 {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})）。
 * 告警不在此 seam —— 走 {@link AlertNotifier}（邮件）。
 */
public interface SmsClient {
    /** 发验证码（按 scene 选模板）。key 未配置→no-op（stub）；configured 但失败→抛 SMS_SEND_FAILED。 */
    void sendVerificationCode(String phone, String code, SmsScene scene);
}
