package com.sks.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sks.AbstractDbTest;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AuthService 的服务级集成测试：真实 Testcontainers PG + Flyway 迁移，验证三级频控与 5 次锁定逻辑。
 */
class AuthServiceTest extends AbstractDbTest {

    @Autowired AuthService authService;
    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired AppUserMapper appUserMapper;

    /** 从 sms_code 表取最近一条真实验证码——MVP 期 SMS 发送留桩，验证码只落库打日志。 */
    private String realCodeOf(String phone) {
        SmsCode code = smsCodeMapper.findMostRecent(phone);
        assertNotNull(code, "sendCode should have written a sms_code row for " + phone);
        return code.getCode();
    }

    @Test
    void rateLimitBlocksSecondSendWithinOneMinute() {
        authService.sendCode("13800000000");
        assertThrows(BizException.class, () -> authService.sendCode("13800000000"));
    }

    @Test
    void loginWithWrongCodeIncrementsErrCount() {
        authService.sendCode("13800000001");
        SmsCode before = smsCodeMapper.findMostRecent("13800000001");
        assertEquals(0, before.getErrCount());
        assertThrows(BizException.class, () -> authService.login("13800000001", "000000"));
        SmsCode after = smsCodeMapper.findMostRecent("13800000001");
        assertEquals(1, after.getErrCount());
    }

    @Test
    void fiveWrongAttemptsLockForTenMinutes() {
        authService.sendCode("13800000002");
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> authService.login("13800000002", "000000"));
        }
        // 第 6 次：即便输入正确验证码也被锁定拒绝（SMS_CODE_LOCKED）
        BizException e =
                assertThrows(
                        BizException.class,
                        () -> authService.login("13800000002", realCodeOf("13800000002")));
        assertEquals(ErrorCode.SMS_CODE_LOCKED, e.errorCode());
    }

    @Test
    void lockAlsoBlocksFurtherSendCode() {
        authService.sendCode("13800000003");
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> authService.login("13800000003", "000000"));
        }
        // 锁定期间发新码也被拒
        BizException e =
                assertThrows(BizException.class, () -> authService.sendCode("13800000003"));
        assertEquals(ErrorCode.SMS_CODE_LOCKED, e.errorCode());
    }

    @Test
    void loginWithCorrectCodeSucceedsAndRegistersNewUser() {
        String phone = "13800000004";
        authService.sendCode(phone);
        AuthService.LoginResult result = authService.login(phone, realCodeOf(phone));
        assertNotNull(result.token());
        assertNotNull(result.userId());
        assertTrue(result.isNew());
        // 落库后 app_user 应能按手机号查到
        AppUser user = appUserMapper.findByPhone(phone);
        assertNotNull(user);
        assertEquals(phone, user.getPhone());
        // 验证码已被标记为已使用
        assertTrue(smsCodeMapper.findMostRecent(phone).getUsed());
    }

    @Test
    void loginForExistingUserReportsNotNew() {
        // 预置 app_user（模拟已注册用户），再发码、登录，应返回 isNew=false
        String phone = "13800000005";
        AppUser existing = new AppUser();
        existing.setPhone(phone);
        existing.setDefaultPlatform("douyin");
        appUserMapper.insert(existing);

        authService.sendCode(phone);
        AuthService.LoginResult result = authService.login(phone, realCodeOf(phone));
        assertNotNull(result.token());
        assertEquals(existing.getId(), result.userId());
        assertTrue(!result.isNew());
    }
}
