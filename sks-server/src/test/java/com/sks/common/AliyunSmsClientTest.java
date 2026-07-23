package com.sks.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import org.junit.jupiter.api.Test;

/**
 * {@link AliyunSmsClient} 单测——Mockito mock {@link Client}，不真打阿里云。
 *
 * <p>承重断言（spec §6）：
 * <ul>
 *   <li>未 configured（key 空）→ 不抛、不发；
 *   <li>configured + code=OK → 不抛；
 *   <li>configured + code≠OK → 抛 SMS_SEND_FAILED；
 *   <li>configured + 异常 → 抛 SMS_SEND_FAILED。
 * </ul>
 */
class AliyunSmsClientTest {

    /** configured 客户端：keys/templates 全非空 + 注入 mock Client（跳过懒构建）。 */
    private static AliyunSmsClient newConfigured(Client mockClient) {
        AliyunSmsClient c = new AliyunSmsClient(
                "ak", "sk", "sign", "SMS_VERIFY", "SMS_ALERT", "dysmsapi.aliyuncs.com");
        c.setDelegate(mockClient);
        return c;
    }

    /** 未 configured：全空。 */
    private static AliyunSmsClient newUnconfigured() {
        return new AliyunSmsClient("", "", "", "", "", "dysmsapi.aliyuncs.com");
    }

    private static SendSmsResponse resp(String code) {
        SendSmsResponse r = new SendSmsResponse();
        SendSmsResponseBody body = new SendSmsResponseBody();
        body.setCode(code);
        body.setBizId("biz-1");
        body.setMessage("msg");
        r.setBody(body);
        return r;
    }

    @Test
    void unconfiguredStubDoesNotThrowOrSend() {
        AliyunSmsClient c = newUnconfigured();
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456"));
    }

    @Test
    void configuredOkDoesNotThrow() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenReturn(resp("OK"));
        AliyunSmsClient c = newConfigured(mock);
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456"));
        verify(mock).sendSms(any(SendSmsRequest.class));
    }

    @Test
    void configuredNonOkThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenReturn(resp("isv.BUSINESS_LIMIT_CONTROL"));
        AliyunSmsClient c = newConfigured(mock);
        BizException e = assertThrows(BizException.class,
                () -> c.sendVerificationCode("13900000000", "123456"));
        // BizException 暴露的是 errorCode()（非 getCode()），据此断言具体错误码。
        assertEquals(ErrorCode.SMS_SEND_FAILED, e.errorCode());
    }

    @Test
    void configuredExceptionThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenThrow(new RuntimeException("timeout"));
        AliyunSmsClient c = newConfigured(mock);
        assertThrows(BizException.class, () -> c.sendVerificationCode("13900000000", "123456"));
    }

    @Test
    void alertUnconfiguredStubNoThrow() {
        AliyunSmsClient c = newUnconfigured();
        assertDoesNotThrow(() -> c.sendAlert("13900000000", "短信余额不足"));
    }
}
