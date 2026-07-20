package com.sks.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sks.AbstractDbTest;
import com.sks.auth.AuthService;
import com.sks.auth.SmsCodeMapper;
import com.sks.credit.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link RechargeOrderService} 服务级集成测试——覆盖注册体验额度、首充口径、复购、补偿四条核心资金路径。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway V1/V2 建表 + 种子。四个用例取自
 * Task 0.7 brief 原文，覆盖：
 *
 * <ol>
 *   <li>注册钩子送体验额度（trial 单 + 3 条，{@code sks.trial-credit} 默认 3）
 *   <li>首充 = 套餐 + 首充赠送（trial→done 转换，is_first_charge=true，bonus 10）
 *   <li>复购不享赠送、新建 done 单
 *   <li>补偿单不触发首充（{@code order_type='compensate'} 天然排除），后续开通仍享首充 bonus
 * </ol>
 *
 * <p>{@code @TestPropertySource} 注入 {@code ADMIN_SEED_USERNAME/PASSWORD}，让 {@link AdminSeedRunner}
 * 在上下文启动时回填真实站长账号；测试通过 {@link AdminUserMapper#findByUsername} 拿到 adminId。
 * 注册辅助 {@link #registerUser} 走真实 {@link AuthService#login} 链路（发码→取真码→登录），触发注册钩子。
 */
@TestPropertySource(
        properties = {
            "ADMIN_SEED_USERNAME=admin",
            "ADMIN_SEED_PASSWORD=test-admin-pwd"
        })
class RechargeOrderServiceTest extends AbstractDbTest {

    @Autowired RechargeOrderService rechargeOrderService;
    @Autowired CreditService creditService;
    @Autowired AuthService authService;
    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired AdminUserMapper adminUserMapper;

    /** 从 sms_code 表取最近一条真实验证码——MVP 期 SMS 发送留桩，验证码只落库打日志。 */
    private String realCodeOf(String phone) {
        var code = smsCodeMapper.findMostRecent(phone);
        assertNotNull(code, "sendCode should have written a sms_code row for " + phone);
        return code.getCode();
    }

    /**
     * 注册辅助：走真实 {@link AuthService#login} 链路（发码→取真码→登录），触发注册钩子
     * （ensureAccount + trial 单 + 体验额度）。每个用例用不同手机号，互不干扰频控。
     */
    private long registerUser(String phone) {
        authService.sendCode(phone);
        var result = authService.login(phone, realCodeOf(phone));
        return result.userId();
    }

    /** 拿种子回填后的站长 id（AdminSeedRunner 已在上下文启动时回填 admin/test-admin-pwd）。 */
    private long adminId() {
        AdminUser admin = adminUserMapper.findByUsername("admin");
        assertNotNull(admin, "AdminSeedRunner should have seeded 'admin' user");
        return admin.getId();
    }

    @Test
    void registrationGrantsTrialCredit() {
        long uid = registerUser("13800000001"); // 触发注册钩子
        assertEquals(3, creditService.balance(uid)); // 注册送体验额度（sks.trial-credit，测试环境按默认值 3 断言）
        assertEquals("trial", rechargeOrderService.latestOrder(uid).getStatus());
    }

    @Test
    void firstChargeGrantsPackagePlusBonus() {
        long uid = registerUser("13800000002"); // 注册钩子：trial 单 + 3 条体验额度
        long adminId = adminId();
        rechargeOrderService.open(uid, "p50", adminId);
        assertEquals(63, creditService.balance(uid)); // 3 体验 + 50 套餐 + 10 首充赠送
        assertEquals("done", rechargeOrderService.latestOrder(uid).getStatus());
    }

    @Test
    void repeatChargeNoBonusAndNewOrder() {
        long uid = registerUser("13800000003");
        long adminId = adminId();
        rechargeOrderService.open(uid, "p50", adminId); // 首充 → 63
        rechargeOrderService.open(uid, "p150", adminId); // 复购 +150
        assertEquals(63 + 150, creditService.balance(uid)); // 无第二次赠送
    }

    @Test
    void compensationAddsCreditWithoutTriggeringFirstChargeBonus() {
        long uid = registerUser("13800000004"); // 注册 +3
        long adminId = adminId();
        rechargeOrderService.compensate(uid, 5, "7/18 服务不可用补偿", adminId);
        assertEquals(8, creditService.balance(uid)); // 3 + 5，无首充赠送
        assertEquals("done", rechargeOrderService.latestOrder(uid).getStatus()); // 补偿单留痕
        rechargeOrderService.open(uid, "p50", adminId); // 补偿单不算首充
        assertEquals(8 + 50 + 10, creditService.balance(uid)); // 开通仍享首充 bonus
    }
}
