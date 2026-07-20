package com.sks.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sks.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** UserService 的服务级集成测试：profile 更新与 completeness 重算。 */
class UserServiceTest extends AbstractDbTest {

    @Autowired UserService userService;
    @Autowired AppUserMapper appUserMapper;

    /** 测试辅助：直接插入一条 app_user（不经过 AuthService，避免频控/锁干扰）。 */
    private long registerUser(String phone) {
        AppUser u = new AppUser();
        u.setPhone(phone);
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        return u.getId();
    }

    @Test
    void updatingProfileRecomputesCompleteness() {
        long uid = registerUser("13800000009");
        userService.update(uid, UpdateMe.of("装修老张", "全屋定制", null, null, null)); // 5 字段填 2
        assertEquals(40, userService.me(uid).completeness());
    }

    @Test
    void fullyFilledProfileScores100() {
        long uid = registerUser("13800000010");
        userService.update(
                uid,
                UpdateMe.of("装修老张", "全屋定制", "设计师", "口播", 3)); // 5 字段全填
        assertEquals(100, userService.me(uid).completeness());
    }

    @Test
    void meReturnsPersistedProfileFields() {
        long uid = registerUser("13800000011");
        // 用全字段构造器（基础资料 + 主平台都填，但创作资料只填 1 个 → completeness=20）
        userService.update(
                uid,
                new UpdateMe("装修老张", null, 30, "北京", null, null, null, null, "kuaishou"));
        UserService.MeResponse me = userService.me(uid);
        assertEquals("装修老张", me.nickname());
        assertEquals(30, me.age());
        assertEquals("北京", me.city());
        assertEquals("kuaishou", me.defaultPlatform());
        assertEquals(20, me.completeness()); // 1/5 = 20
        // balance 占位为 0，Task 0.5 接线真实余额
        assertEquals(0, me.balance());
        assertNotNull(me.phone());
    }
}
