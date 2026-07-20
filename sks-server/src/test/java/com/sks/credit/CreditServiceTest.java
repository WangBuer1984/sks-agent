package com.sks.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sks.AbstractDbTest;
import com.sks.common.BizException;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CreditService} 服务级集成测试——资金核心，测试最厚。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建表。覆盖：余额不足 /
 * 退款幂等 / 充值幂等 / 20 线程并发扣减不超扣 / 并发重复退款与充值只应用一次。
 *
 * <p><b>事务边界（关键）：</b>基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚，跨测试隔离）。
 * 但<b>并发用例不能跑在被回滚的测试事务里</b>——工作线程看不到本线程未提交的 setup 余额（{@code ok=0} 全部
 * 余额不足），且 {@code credit_account} 行锁会让工作线程的原子 UPDATE 互等到死锁/超时。故并发用例标注
 * {@code @Transactional(propagation = NOT_SUPPORTED)} 挂起测试事务：setup / 每次服务调用各自独立提交，
 * 工作线程能读到已提交余额；{@link #cleanup} 显式清理这些已提交行（对回滚型用例为空操作）。
 */
class CreditServiceTest extends AbstractDbTest {

    @Autowired CreditService creditService;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String PHONE = "13900000005";
    private long uid;

    @BeforeEach
    void registerUser() {
        AppUser u = new AppUser();
        u.setPhone(PHONE);
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        // 并发用例（NOT_SUPPORTED）不随测试事务回滚，需显式清理；回滚型用例的删除发生在回滚前/后均无副作用。
        jdbcTemplate.update("DELETE FROM credit_ledger WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM credit_account WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", uid);
    }

    @Test
    void deductFailsWhenInsufficient() {
        creditService.credit(uid, 3, "recharge", "o1", null);
        assertThrows(BizException.class, () -> creditService.deduct(uid, 5, "generate", "s1"));
        assertEquals(3, creditService.balance(uid));
    }

    @Test
    void refundIsIdempotent() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        creditService.deduct(uid, 1, "generate", "s1");
        creditService.refund(uid, 1, "generate", "s1");
        creditService.refund(uid, 1, "generate", "s1"); // 重复不应多退
        assertEquals(10, creditService.balance(uid));
    }

    @Test
    void creditIsIdempotent() {
        creditService.credit(uid, 50, "recharge", "o2", null);
        creditService.credit(uid, 50, "recharge", "o2", null); // 同订单重复入账应为 no-op
        assertEquals(50, creditService.balance(uid));
    }

    // 挂起测试事务：setup credit 与每次 deduct 各自独立提交，工作线程才能读到已提交的 balance=10。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeductNeverOverspends() throws Exception {
        creditService.credit(uid, 10, "recharge", "o1", null);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    creditService.deduct(uid, 1, "generate", "s" + idx);
                    ok.incrementAndGet();
                } catch (BizException ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(10, ok.get()); // 恰好 10 次成功
        assertEquals(0, creditService.balance(uid)); // 绝不超扣为负
    }

    // 并发重复退款：20 线程同 biz_id refund——唯一约束兜底，只退一次，输家静默 no-op 不抛异常。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateRefundOnlyAppliesOnce() throws Exception {
        creditService.ensureAccount(uid);
        creditService.credit(uid, 1, "recharge", "r1", null);
        creditService.deduct(uid, 1, "generate", "d1"); // balance 0
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    creditService.refund(uid, 1, "generate", "d1");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get());
        assertEquals(1, creditService.balance(uid)); // 只退一次
    }

    // 并发重复充值：20 线程同 biz_id credit——唯一约束兜底，只入账一次，输家静默 no-op 不抛异常。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateCreditOnlyAppliesOnce() throws Exception {
        creditService.ensureAccount(uid);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    creditService.credit(uid, 5, "bonus", "dup1", null);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get()); // 幂等：并发重复静默 no-op，不抛
        assertEquals(5, creditService.balance(uid)); // 只入账一次
    }

    @Test
    void balanceReturnsZeroWhenNoAccount() {
        // 未建账的用户 balance 返回 0（不抛），与 ensureAccount 语义一致。
        assertEquals(0, creditService.balance(uid));
    }

    @Test
    void ensureAccountIsIdempotent() {
        creditService.ensureAccount(uid);
        creditService.ensureAccount(uid); // 重复调用不报错、不叠加
        assertEquals(0, creditService.balance(uid));
    }

    // 回归：refund 对未建账用户调用——必须建账 + 正确加额度，且不留下阻塞未来重试的孤儿流水。
    // 修复前：refund 不 ensureAccount、忽略 addBalance 返回值，缺账时流水写入但余额未动，未来同 biz_id 合法重试被永久阻塞。
    @Test
    void refundOnMissingAccountAppliesAndDoesNotLeaveOrphanLedger() {
        // 未建账、无先前 deduct，直接退款。
        creditService.refund(uid, 5, "generate", "orphan1");
        // 账户被自动建立且余额 == n（修复前为静默 0）。
        assertEquals(5, creditService.balance(uid));
        // 同 biz_id 第二次退款应为幂等 no-op，不重复加额度（孤儿流水不存在，重试不被阻塞）。
        creditService.refund(uid, 5, "generate", "orphan1");
        assertEquals(5, creditService.balance(uid));
    }
}
