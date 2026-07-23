package com.sks.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** sms_code scene 化 + existsLocked 锁定判定（spec §3.0 Critical）。 */
class SmsCodeMapperTest extends AbstractDbTest {

    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired JdbcTemplate jdbc;

    private void insertCode(String phone, String scene, int errCount, String createdAtMinus) {
        jdbc.update(
                "INSERT INTO sms_code(phone, code, expire_at, err_count, used, scene, created_at) "
                        + "VALUES (?,?,now()+interval '5 min',?,false,?,now()-interval '"
                        + createdAtMinus + "')",
                phone, "123456", errCount, scene);
    }

    @Test
    void findActiveCodeFiltersByScene() {
        insertCode("13900000010", "LOGIN_REGISTER", 0, "10 second");
        insertCode("13900000010", "VERIFY_OLD_PHONE", 0, "5 second");
        // 取 LOGIN_REGISTER 的行，不混到 VERIFY_OLD_PHONE
        SmsCode login = smsCodeMapper.findActiveCode("13900000010", "LOGIN_REGISTER");
        assertNotNull(login);
        assertEquals("LOGIN_REGISTER", login.getScene());
        // 跨 scene 拿不到：VERIFY_OLD_PHONE 的码不能当登录码
        SmsCode change = smsCodeMapper.findActiveCode("13900000010", "VERIFY_OLD_PHONE");
        assertNotNull(change);
        assertNotEquals(login.getId(), change.getId());
    }

    @Test
    void existsLockedAnyRowAnyScene() {
        // 4 错 verify-old + 一条更近的 LOGIN_REGISTER 0 错码行 → 仍判锁（ANY-row，防稀释）
        insertCode("13900000011", "VERIFY_OLD_PHONE", 5, "8 minute");
        insertCode("13900000011", "LOGIN_REGISTER", 0, "1 minute");
        assertTrue(smsCodeMapper.existsLocked("13900000011"));
    }

    @Test
    void existsLockedFalseWhenNoFiveErr() {
        insertCode("13900000012", "LOGIN_REGISTER", 4, "1 minute");
        assertFalse(smsCodeMapper.existsLocked("13900000012"));
    }

    @Test
    void existsLockedFalseWhenExpiredLock() {
        // 5 错但 11 分钟前（>10min 锁定窗口）→ 不锁
        insertCode("13900000013", "LOGIN_REGISTER", 5, "11 minute");
        assertFalse(smsCodeMapper.existsLocked("13900000013"));
    }
}
