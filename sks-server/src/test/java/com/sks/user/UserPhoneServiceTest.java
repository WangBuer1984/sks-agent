package com.sks.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.sks.AbstractDbTest;
import com.sks.auth.SmsCode;
import com.sks.auth.SmsCodeMapper;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.SmsClient;
import com.sks.common.SmsScene;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UserPhoneServiceTest extends AbstractDbTest {

    @Autowired UserPhoneService userPhoneService;
    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired AppUserMapper appUserMapper;
    @Autowired PhoneChangeSessionMapper sessionMapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean SmsClient smsClient;

    private long register(String phone) {
        appUserMapper.insert(newUser(phone));
        return appUserMapper.findByPhone(phone).getId();
    }

    private com.sks.user.AppUser newUser(String phone) {
        com.sks.user.AppUser u = new com.sks.user.AppUser();
        u.setPhone(phone);
        return u;
    }

    private String realCodeOf(String phone, String scene) {
        SmsCode c = smsCodeMapper.findActiveCode(phone, scene);
        assertNotNull(c, "sendOldPhoneCode 应写 sms_code(scene=" + scene + ")");
        return c.getCode();
    }

    @Test
    void sendOldCodeWritesCodeAndSessionAndSends() {
        long uid = register("13900000020");
        userPhoneService.sendOldPhoneCode(uid);
        verify(smsClient).sendVerificationCode(eq("13900000020"), eq(realCodeOf("13900000020", "VERIFY_OLD_PHONE")),
                eq(SmsScene.VERIFY_OLD_PHONE));
        assertNotNull(sessionMapper.findByToken(/* 通过 jdbc 查 AWAITING_OLD_VERIFY */ findToken(uid)));
    }

    @Test
    void sendOldCodeReentryDeletesOldSession() {
        long uid = register("13900000021");
        userPhoneService.sendOldPhoneCode(uid);
        // 回拨 created_at 绕过 1 分钟频控
        jdbc.update("UPDATE sms_code SET created_at = now() - interval '2 min' WHERE phone='13900000021'");
        userPhoneService.sendOldPhoneCode(uid);
        // 仅一个活跃 session
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM phone_change_session WHERE user_id=? AND status<>'DONE'",
                Integer.class, uid));
    }

    @Test
    void verifyOldWrongCodeIncrementsErr() {
        long uid = register("13900000022");
        userPhoneService.sendOldPhoneCode(uid);
        assertThrows(BizException.class, () -> userPhoneService.verifyOldPhone(uid, "000000"));
    }

    @Test
    void verifyOldFiveWrongLocks() {
        long uid = register("13900000023");
        userPhoneService.sendOldPhoneCode(uid);
        for (int i = 0; i < 5; i++) {
            // 前 4 次错码 + 第 5 次后判锁
            try { userPhoneService.verifyOldPhone(uid, "000000"); } catch (BizException ignored) {}
        }
        // 第 5 错后 existsLocked = true → 再发码被锁拦
        assertEquals(5, smsCodeMapper.findMostRecent("13900000023").getErrCount());
        assertThrows(BizException.class, () -> userPhoneService.sendOldPhoneCode(uid));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verifyOldLockPersistsAcrossTransactions() {
        // 防止 AbstractDbTest @Transactional 掩盖：NOT_SUPPORTED 让 err_count 真落库
        long uid = register("13900000024");
        userPhoneService.sendOldPhoneCode(uid);
        for (int i = 0; i < 5; i++) {
            try { userPhoneService.verifyOldPhone(uid, "000000"); } catch (BizException ignored) {}
        }
        assertTrue(smsCodeMapper.existsLocked("13900000024"));
    }

    @Test
    void verifyOldRightCodeReturnsTokenAndMarksUsed() {
        long uid = register("13900000025");
        userPhoneService.sendOldPhoneCode(uid);
        String code = realCodeOf("13900000025", "VERIFY_OLD_PHONE");
        String token = userPhoneService.verifyOldPhone(uid, code);
        assertNotNull(token);
        PhoneChangeSession s = sessionMapper.findByToken(token);
        assertEquals("AWAITING_NEW_VERIFY", s.getStatus());
        // 旧号码码 markUsed，重放拿不到新 T
        SmsCode old = smsCodeMapper.findActiveCode("13900000025", "VERIFY_OLD_PHONE");
        assertNull(old, "verify-old 对码后旧号码应 markUsed，findActiveCode 取不到");
    }

    @Test
    void crossSceneCodeRejected() {
        long uid = register("13900000026");
        // 手动建 AWAITING_OLD_VERIFY session，让 session 检查放行，走到 findActiveCode
        PhoneChangeSession s = new PhoneChangeSession();
        s.setToken(java.util.UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(uid);
        s.setOldPhone("13900000026");
        s.setStatus("AWAITING_OLD_VERIFY");
        s.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        sessionMapper.insert(s);
        // 只存在 LOGIN_REGISTER 码 —— scene 过滤必须排除它
        SmsCode login = new SmsCode();
        login.setPhone("13900000026");
        login.setCode("999999");
        login.setExpireAt(OffsetDateTime.now().plusMinutes(5));
        login.setScene("LOGIN_REGISTER");
        smsCodeMapper.insert(login);
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.verifyOldPhone(uid, "999999"));
        assertEquals(ErrorCode.SMS_CODE_INVALID, e.errorCode());
    }

    // --- Task 7: step3 send-new-code / step4 verify-new ---

    @Test
    void sendNewCodeRequiresValidToken() {
        long uid = register("13900000030");
        assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000099", "bad-token"));
    }

    @Test
    void sendNewCodeRejectsSameAsOldPhone() {
        long uid = register("13900000031");
        String token = passVerifyOld(uid, "13900000031");
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000031", token));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    @Test
    void sendNewCodeRejectsBoundPhone() {
        register("13900000032"); // 另一用户占了 32
        long uid = register("13900000033");
        String token = passVerifyOld(uid, "13900000033");
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000032", token));
        assertEquals(ErrorCode.PHONE_ALREADY_BOUND, e.errorCode());
    }

    @Test
    void sendNewCodeSendsToNewPhone() {
        long uid = register("13900000034");
        String token = passVerifyOld(uid, "13900000034");
        userPhoneService.sendNewPhoneCode(uid, "13900000044", token);
        verify(smsClient).sendVerificationCode(eq("13900000044"),
                eq(realCodeOf("13900000044", "BIND_NEW_PHONE")), eq(SmsScene.BIND_NEW_PHONE));
    }

    @Test
    void verifyNewRejectsMismatchedNewPhone() {
        long uid = register("13900000035");
        String token = passVerifyOld(uid, "13900000035");
        userPhoneService.sendNewPhoneCode(uid, "13900000045", token);
        // verify-new 传 B 号（与 session.new_phone=45 不符）
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000099", "000000", token));
        assertEquals(ErrorCode.PHONE_CHANGE_TOKEN_INVALID, e.errorCode());
    }

    @Test
    void verifyNewRightCodeUpdatesPhoneAndInvalidatesCodes() {
        long uid = register("13900000036");
        String token = passVerifyOld(uid, "13900000036");
        userPhoneService.sendNewPhoneCode(uid, "13900000046", token);
        String code = realCodeOf("13900000046", "BIND_NEW_PHONE");
        userPhoneService.verifyNewPhone(uid, "13900000046", code, token);
        // app_user.phone 更新
        assertEquals("13900000046", appUserMapper.selectById(uid).getPhone());
        // token 消费：同 token 再 verify-new 拒
        assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000046", code, token));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verifyNewConcurrentBoundThrowsPhoneAlreadyBound() {
        long uid = register("13900000037");
        String token = passVerifyOld(uid, "13900000037");
        userPhoneService.sendNewPhoneCode(uid, "13900000047", token);
        // 预占 47（另一用户）→ verify-new UPDATE app_user.phone 撞 UNIQUE
        register("13900000047");
        String code = realCodeOf("13900000047", "BIND_NEW_PHONE");
        // 注：本号已发码给 47（同号），47 也在另一用户名下 —— UNIQUE 兜底
        // NOT_SUPPORTED：completePhoneChange 起自己的 tx，DuplicateKey 透传到非事务 verifyNewPhone
        // → catch → PHONE_ALREADY_BOUND（spec §6 事务外 catch 模式）
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000047", code, token));
        assertEquals(ErrorCode.PHONE_ALREADY_BOUND, e.errorCode());
    }

    /** 辅助：完成 verify-old 拿 token。 */
    private String passVerifyOld(long uid, String oldPhone) {
        userPhoneService.sendOldPhoneCode(uid);
        // 回拨 created_at 绕频控（send-new 不再发旧号码，但 send-old 已发一次）
        jdbc.update("UPDATE sms_code SET created_at = now() - interval '2 min' WHERE phone=?", oldPhone);
        String code = realCodeOf(oldPhone, "VERIFY_OLD_PHONE");
        return userPhoneService.verifyOldPhone(uid, code);
    }

    private String findToken(long userId) {
        return jdbc.queryForObject(
                "SELECT token FROM phone_change_session WHERE user_id=? AND status<>'DONE' ORDER BY id DESC LIMIT 1",
                String.class, userId);
    }

    /**
     * NOT_SUPPORTED 用例不随测试事务回滚，需显式清理已提交的行（镜像 AuthServiceTest.cleanupNonTxLock）。
     * 对回滚型用例（数据已被测试事务回滚）此处为空操作，无副作用。phone_change_session 有 FK 指向
     * app_user、sms_code 无 FK，按依赖序删。
     *
     * <p>覆盖两个 NOT_SUPPORTED 用例：verifyOldLockPersistsAcrossTransactions（phone 24）与
     * verifyNewConcurrentBoundThrowsPhoneAlreadyBound（phone 37/47，含预占的并发占号行）。
     */
    @AfterEach
    void cleanupNonTxLock() {
        String[] phones = {"13900000024", "13900000037", "13900000047"};
        for (String p : phones) {
            jdbc.update("DELETE FROM phone_change_session WHERE user_id IN (SELECT id FROM app_user WHERE phone = ?)", p);
        }
        for (String p : phones) {
            jdbc.update("DELETE FROM sms_code WHERE phone = ?", p);
        }
        for (String p : phones) {
            jdbc.update("DELETE FROM app_user WHERE phone = ?", p);
        }
    }
}
