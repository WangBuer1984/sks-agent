package com.sks.user;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.AbstractDbTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PhoneChangeSessionMapperTest extends AbstractDbTest {

    @Autowired PhoneChangeSessionMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private PhoneChangeSession newSession(String token, long userId, String oldPhone) {
        PhoneChangeSession s = new PhoneChangeSession();
        s.setToken(token);
        s.setUserId(userId);
        s.setOldPhone(oldPhone);
        s.setStatus("AWAITING_OLD_VERIFY");
        s.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        return s;
    }

    @Test
    void insertAndFindByToken() {
        long uid = ensureUser("13900000001");
        mapper.insert(newSession("tok-1", uid, "13900000001"));
        PhoneChangeSession s = mapper.findByToken("tok-1");
        assertNotNull(s);
        assertEquals(uid, s.getUserId());
        assertEquals("AWAITING_OLD_VERIFY", s.getStatus());
    }

    @Test
    void deleteActiveByUserIdRemovesNonDoneThenInsertSucceeds() {
        long uid = ensureUser("13900000002");
        mapper.insert(newSession("tok-2", uid, "13900000002"));
        // 重入：先删旧行再建新行（不撞部分唯一索引）
        assertEquals(1, mapper.deleteActiveByUserId(uid));
        mapper.insert(newSession("tok-3", uid, "13900000002"));
        assertNotNull(mapper.findByToken("tok-3"));
    }

    @Test
    void partialUniqueIndexBlocksSecondActiveSession() {
        long uid = ensureUser("13900000003");
        mapper.insert(newSession("tok-4", uid, "13900000003"));
        // 不先删直接建第二行活跃 session → 撞 UNIQUE(user_id) WHERE status<>'DONE'
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> mapper.insert(newSession("tok-5", uid, "13900000003")));
    }

    private long ensureUser(String phone) {
        return jdbc.queryForObject(
                "INSERT INTO app_user(phone) VALUES (?) ON CONFLICT (phone) DO UPDATE SET phone=EXCLUDED.phone RETURNING id",
                Long.class, phone);
    }
}
