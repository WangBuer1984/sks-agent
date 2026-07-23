package com.sks.common;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云 Dysmsapi SendSms 实现（§3 联调首检「阿里云短信」）。
 *
 * <p><b>条件降级</b>：access-key / secret / sign / 对应 template 任一为空 → 走 stub（log + no-op，
 * <b>不抛</b>），本地 / CI 无 key 行为同留桩期；齐全 → 真 {@code SendSms}。失败（{@code body.code != "OK"}
 * 或异常）抛 {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})。
 *
 * <p><b>懒构建 Client</b>：仅 configured 时 {@code new Client(config)}，避免空 key 启动期炸；
 * 实例缓存。测试经 {@link #setDelegate(Client)} 注入 mock 跳过懒构建。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-07-23-aliyun-sms-wiring-design.md}。
 */
@Component
public class AliyunSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsClient.class);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String verifyTemplateCode;
    private final String alertTemplateCode;
    private final String endpoint;

    /** 测试 seam：非空时优先用，跳过懒构建。 */
    private Client override;
    /** 懒构建的真实 client，缓存。 */
    private Client built;

    public AliyunSmsClient(
            @Value("${sks.sms.access-key-id:}") String accessKeyId,
            @Value("${sks.sms.access-key-secret:}") String accessKeySecret,
            @Value("${sks.sms.sign-name:}") String signName,
            @Value("${sks.sms.verify-template-code:}") String verifyTemplateCode,
            @Value("${sks.sms.alert-template-code:}") String alertTemplateCode,
            @Value("${sks.sms.endpoint:dysmsapi.aliyuncs.com}") String endpoint) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.verifyTemplateCode = verifyTemplateCode;
        this.alertTemplateCode = alertTemplateCode;
        this.endpoint = endpoint;
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        if (!configured(verifyTemplateCode)) {
            log.info("[SMS-STUB] send code to phone={}: code={} (no ALIYUN key/template)", phone, code);
            return;
        }
        send(phone, verifyTemplateCode, "{\"code\":\"" + code + "\"}");
    }

    @Override
    public void sendAlert(String phone, String reason) {
        if (!configured(alertTemplateCode)) {
            log.warn("[SMS-STUB] quota alert to admin={}: {} (no ALIYUN alert template)", phone, reason);
            return;
        }
        send(phone, alertTemplateCode, "{\"reason\":\"" + truncate(reason, 200) + "\"}");
    }

    private void send(String phone, String templateCode, String templateParam) {
        SendSmsRequest req = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam(templateParam);
        try {
            SendSmsResponse resp = delegate().sendSms(req);
            String code = resp.getBody().getCode();
            if (!"OK".equals(code)) {
                log.warn("AliyunSms send failed: phone={}, code={}, msg={}",
                        phone, code, resp.getBody().getMessage());
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
            log.info("AliyunSms sent: phone={}, bizId={}", phone, resp.getBody().getBizId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AliyunSms send exception: phone={}: {}", phone, e.getMessage());
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    private boolean configured(String templateCode) {
        return isPresent(accessKeyId) && isPresent(accessKeySecret)
                && isPresent(signName) && isPresent(templateCode);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private Client delegate() {
        if (override != null) {
            return override;
        }
        if (built == null) {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = endpoint;
            try {
                built = new Client(config);
            } catch (Exception e) {
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
        }
        return built;
    }

    /** 测试 seam：注入 mock Client，跳过懒构建。 */
    void setDelegate(Client delegate) {
        this.override = delegate;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
