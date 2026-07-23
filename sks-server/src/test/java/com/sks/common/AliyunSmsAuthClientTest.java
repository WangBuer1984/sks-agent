package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import org.junit.jupiter.api.Test;

/** AliyunSmsAuthClient 单测——mock dypnsapi Client，不真打阿里云。spec §7。 */
class AliyunSmsAuthClientTest {

    private static AliyunSmsAuthClient newConfigured(Client mock) {
        AliyunSmsAuthClient c = new AliyunSmsAuthClient(
                "ak", "sk", "dypnsapi.aliyuncs.com", "速通互联验证码",
                "100002", "100003", "100004");
        c.setDelegate(mock);
        return c;
    }

    private static AliyunSmsAuthClient newUnconfigured() {
        return new AliyunSmsAuthClient("", "", "dypnsapi.aliyuncs.com", "", "", "", "");
    }

    private static SendSmsVerifyCodeResponse resp(String code) {
        SendSmsVerifyCodeResponse r = new SendSmsVerifyCodeResponse();
        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody();
        body.setCode(code);
        body.setMessage("msg");
        r.setBody(body);
        return r;
    }

    @Test
    void unconfiguredStubDoesNotThrowOrSend() {
        assertDoesNotThrow(() -> newUnconfigured()
                .sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
    }

    @Test
    void configuredOkDoesNotThrow() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any(RuntimeOptions.class))).thenReturn(resp("OK"));
        AliyunSmsAuthClient c = newConfigured(mock);
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
        verify(mock).sendSmsVerifyCodeWithOptions(any(SendSmsVerifyCodeRequest.class), any());
    }

    @Test
    void configuredNonOkThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(resp("isv.BUSINESS_LIMIT_CONTROL"));
        BizException e = assertThrows(BizException.class,
                () -> newConfigured(mock).sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
        assertEquals(ErrorCode.SMS_SEND_FAILED, e.errorCode());
    }

    @Test
    void configuredExceptionThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenThrow(new RuntimeException("timeout"));
        assertThrows(BizException.class,
                () -> newConfigured(mock).sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
    }

    @Test
    void sceneMapsToTemplateCode() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(resp("OK"));
        AliyunSmsAuthClient c = newConfigured(mock);
        c.sendVerificationCode("13900000000", "123456", SmsScene.VERIFY_OLD_PHONE);
        c.sendVerificationCode("13900000001", "654321", SmsScene.BIND_NEW_PHONE);
        // ArgumentCaptor 验证两次调用分别带 100003 / 100004
        org.mockito.ArgumentCaptor<SendSmsVerifyCodeRequest> cap =
                org.mockito.ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        verify(mock, times(2)).sendSmsVerifyCodeWithOptions(cap.capture(), any());
        assertEquals("100003", cap.getAllValues().get(0).getTemplateCode());
        assertEquals("100004", cap.getAllValues().get(1).getTemplateCode());
        // TemplateParam 含 code + min（首次调用 code=123456；ArgumentCaptor.getValue() 返回末次，
        // 故取 get(0) 校验 123456）
        assertTrue(cap.getAllValues().get(0).getTemplateParam().contains("\"code\":\"123456\""));
        assertTrue(cap.getAllValues().get(0).getTemplateParam().contains("\"min\":\"5\""));
    }
}
