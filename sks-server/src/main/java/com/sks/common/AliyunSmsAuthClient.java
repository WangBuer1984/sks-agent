package com.sks.common;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DYPNS 短信认证实现（spec §3.3，替换 dysmsapi 版 AliyunSmsClient）。
 *
 * <p>字面码模式：{@code TemplateParam={"code":"<6>","min":"5"}}，阿里云只发不核验，Java 自生成码 + 自比对
 * （不调 CheckSmsVerifyCode）。条件降级：access-key/secret/sign + 对应 scene 模板任一空 → stub 不抛；
 * 齐全 → 真 SendSmsVerifyCode，失败（body.Code≠OK 或异常）抛 SMS_SEND_FAILED。懒构造 Client；setDelegate 测试 seam。
 *
 * <p><b>SDK 2.0.0 setter 类型</b>（以 jar 实测为准，非 brief 字面量）：
 * {@code setCodeLength(Long)} / {@code setCodeType(Long)} / {@code setInterval(Long)} —— 均为 {@code Long}，
 * 故传字面量 {@code 6L} / {@code 1L} / {@code 60L}（int 不会 autobox 到 Long，须显式 {@code L}）。
 */
@Component
public class AliyunSmsAuthClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsAuthClient.class);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String endpoint;
    private final String signName;
    private final String templateLogin;
    private final String templateVerifyOld;
    private final String templateBindNew;

    private Client override;
    private Client built;

    public AliyunSmsAuthClient(
            @Value("${sks.sms.access-key-id:}") String accessKeyId,
            @Value("${sks.sms.access-key-secret:}") String accessKeySecret,
            @Value("${sks.sms.endpoint:dypnsapi.aliyuncs.com}") String endpoint,
            @Value("${sks.sms.sign-name:}") String signName,
            @Value("${sks.sms.template-login:}") String templateLogin,
            @Value("${sks.sms.template-verify-old:}") String templateVerifyOld,
            @Value("${sks.sms.template-bind-new:}") String templateBindNew) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.endpoint = endpoint;
        this.signName = signName;
        this.templateLogin = templateLogin;
        this.templateVerifyOld = templateVerifyOld;
        this.templateBindNew = templateBindNew;
    }

    @Override
    public void sendVerificationCode(String phone, String code, SmsScene scene) {
        String templateCode = templateFor(scene);
        if (!configured(templateCode)) {
            log.info("[SMS-STUB] scene={} phone={} code={} (no ALIYUN key/template)", scene, phone, code);
            return;
        }
        send(phone, templateCode, code);
    }

    private void send(String phone, String templateCode, String code) {
        SendSmsVerifyCodeRequest req = new SendSmsVerifyCodeRequest()
                .setPhoneNumber(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam("{\"code\":\"" + code + "\",\"min\":\"5\"}")
                .setCodeLength(6L)
                .setCodeType(1L)
                .setInterval(60L);
        try {
            SendSmsVerifyCodeResponse resp = delegate().sendSmsVerifyCodeWithOptions(req, new RuntimeOptions());
            String c = resp.getBody().getCode();
            if (!"OK".equals(c)) {
                log.warn("AliyunSmsAuth send failed: phone={}, code={}, msg={}",
                        phone, c, resp.getBody().getMessage());
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
            log.info("AliyunSmsAuth sent: phone={}, bizId={}", phone,
                    resp.getBody().getModel() == null ? null : resp.getBody().getModel().getBizId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AliyunSmsAuth send exception: phone={}: {}", phone, e.getMessage());
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    private String templateFor(SmsScene scene) {
        return switch (scene) {
            case LOGIN_REGISTER -> templateLogin;
            case VERIFY_OLD_PHONE -> templateVerifyOld;
            case BIND_NEW_PHONE -> templateBindNew;
        };
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
}
