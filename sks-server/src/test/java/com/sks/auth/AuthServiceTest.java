package com.sks.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sks.AbstractDbTest;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.SmsClient;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * AuthService 的服务级集成测试：真实 Testcontainers PG + Flyway 迁移，验证三级频控与 5 次锁定逻辑。
 */
class AuthServiceTest extends AbstractDbTest {

    @Autowired AuthService authService;
    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean SmsClient smsClient;

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

    /**
     * 非事务锁测试（Critical 回归兜底）：证明 {@code err_count} 跨独立事务持久化、5 次后锁定真正生效。
     *
     * <p>基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚），会掩盖
     * 「{@code login @Transactional} 在抛 {@code BizException} 时回滚 {@code err_count}」的回归——测试同连接
     * 读到未提交自增、方法结束才整体回滚，{@code fiveWrongAttemptsLockForTenMinutes} 因此「假绿」。本用例
     * 标 {@code @Transactional(propagation = NOT_SUPPORTED)} 挂起测试事务：每次 {@code login}（非事务）的
     * {@code incrementErrCount} 立即自动提交到真实 DB；若 {@code login} 被误标 {@code @Transactional}，
     * {@code err_count} 会被回滚、{@code checkLocked} 永不命中 → 第 6 次不抛 {@code SMS_CODE_LOCKED} → 本用例红。
     * {@link #cleanupNonTxLock} 显式清理已提交行。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fiveWrongAttemptsLockPersistsAcrossTransactions() {
        String phone = "13800000010";
        authService.sendCode(phone); // 自动提交一条验证码
        // 5 次错码：每次 login 非事务 → incrementErrCount 自动提交、跨调用累积
        for (int i = 0; i < 5; i++) {
            BizException e =
                    assertThrows(
                            BizException.class,
                            () -> authService.login(phone, "000000"));
            assertEquals(ErrorCode.SMS_CODE_INVALID, e.errorCode());
        }
        // 第 6 次：即便输入正确码，也必须因 err_count>=5 被锁定（证明自增跨事务持久化、锁定真正生效）
        BizException locked =
                assertThrows(
                        BizException.class,
                        () -> authService.login(phone, realCodeOf(phone)));
        assertEquals(ErrorCode.SMS_CODE_LOCKED, locked.errorCode());
    }

    @AfterEach
    void cleanupNonTxLock() {
        // NOT_SUPPORTED 用例不随测试事务回滚，需显式清理已提交的行。对回滚型用例（数据已被测试事务回滚）
        // 此处为空操作，无副作用。recharge_order 有 FK 指向 app_user，须先删 recharge_order 再删 app_user。
        jdbcTemplate.update("DELETE FROM sms_code WHERE phone = '13800000010'");
        jdbcTemplate.update(
                "DELETE FROM recharge_order WHERE user_id IN (SELECT id FROM app_user WHERE phone = '13800000010')");
        jdbcTemplate.update(
                "DELETE FROM credit_ledger WHERE user_id IN (SELECT id FROM app_user WHERE phone = '13800000010')");
        jdbcTemplate.update(
                "DELETE FROM credit_account WHERE user_id IN (SELECT id FROM app_user WHERE phone = '13800000010')");
        jdbcTemplate.update("DELETE FROM app_user WHERE phone = '13800000010'");
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

    @Test
    void sendCodeDelegatesToSmsClient() {
        String phone = "13900000099";
        authService.sendCode(phone);
        SmsCode row = smsCodeMapper.findMostRecent(phone);
        assertNotNull(row, "sendCode 应落 sms_code 行");
        verify(smsClient).sendVerificationCode(eq(phone), eq(row.getCode()));
    }
}
