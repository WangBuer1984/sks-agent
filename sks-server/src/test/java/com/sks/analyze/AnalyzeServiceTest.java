package com.sks.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.credit.CreditService;
import com.sks.topic.TopicMapper;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AnalyzeService} + {@link AnalyzeTaskPoller} 服务级集成测试——§4.3 异步拆解额度事务链
 * （产品 #1 资金不变量：按比例退款、永不漏扣、永不双退）。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建 analyze_task /
 * benchmark_video / topic。{@link AiClient} 用 {@code @MockBean} mock——不真正调 Python。
 *
 * <p><b>事务边界 + 测试隔离（§4.1 教训同款，stored memory {@code abstract-db-test-masks-tx-rollback}）：</b>
 *
 * <ol>
 *   <li>{@link AnalyzeService} 编排方法本身<b>不加 {@code @Transactional}</b>——长 HTTP 调用
 *       （{@link AiClient#analyzeAccount} 等）在任何事务之外；{@link CreditService#deduct} /
 *       {@link CreditService#refund} 各自是 {@code @Transactional(REQUIRED)} 独立短事务（从非事务方法
 *       调用 → 各自提交）。
 *   <li>基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚）。但本测试<b>必须</b>
 *       标 {@code @Transactional(propagation = NOT_SUPPORTED)} 挂起测试事务：否则 setup credit +
 *       deduct + refund 全部 JOIN 测试事务（未提交）→ 方法结束整体回滚 → {@code balance()} 读到的是
 *       自己未提交的值 → {@code assertEquals(5, balance)} 因「deduct 从未持久化、refund 从未执行」
 *       而<b>假绿</b>。挂起后各次服务调用独立提交到真实 DB，refund 持久化才被真正证明。
 *   <li>{@link #cleanup} 显式清理已提交行（NOT_SUPPORTED 不随测试事务回滚）——FK 安全顺序：
 *       benchmark_video → analyze_task → credit_ledger → credit_account → topic → app_user。
 *   <li><b>每条退款断言都附加 refund-ledger-count</b>（{@code SELECT COUNT(*) FROM credit_ledger
 *       WHERE ... type='refund'}）——仅 {@code balance} 断言会因「deduct+refund 都回滚净零」而假绿，
 *       退款流水行数才是「refund 真正落库」的可证伪断言（stored memory 明确要求，非协商）。
 * </ol>
 */
class AnalyzeServiceTest extends AbstractDbTest {

    @Autowired AnalyzeService analyzeService;
    @Autowired AnalyzeTaskMapper analyzeTaskMapper;
    @Autowired AnalyzeTaskPoller poller;
    @Autowired CreditService creditService;
    @Autowired AppUserMapper appUserMapper;
    @Autowired TopicMapper topicMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean AiClient aiClient;

    private long uid;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        AppUser u = new AppUser();
        u.setPhone("13900000333");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        // FK 安全顺序：benchmark_video → analyze_task → credit_ledger → credit_account → topic → app_user。
        jdbcTemplate.update(
                "DELETE FROM benchmark_video WHERE analyze_task_id IN "
                        + "(SELECT id FROM analyze_task WHERE user_id = ?)",
                uid);
        jdbcTemplate.update("DELETE FROM analyze_task WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM credit_ledger WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM credit_account WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM topic WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", uid);
    }

    // ---- 退款流水计数辅助（承重断言：refund 真正落库）----

    private int refundCount(String bizType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_ledger WHERE user_id = ? AND biz_type = ? AND type = 'refund'",
                Integer.class,
                uid,
                bizType);
    }

    private int failedCount(String taskType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analyze_task WHERE user_id = ? AND status = 'failed' AND task_type = ?",
                Integer.class,
                uid,
                taskType);
    }

    // ---- brief 用例 1：预检失败不扣费 ----

    /** §4.3：预检不可达 / video_count=0 → 直接拒绝，不扣费、不建任务。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accountPrecheckFailureDoesNotCharge() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(false, 0));
        assertThrows(BizException.class, () -> analyzeService.startAccount(uid, "bad-url"));
        assertEquals(10, creditService.balance(uid)); // 未扣
        // 承重：无退款流水（没扣过就不该退）。无 analyze_task 行。
        assertEquals(0, refundCount("analyze_account"));
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM analyze_task WHERE user_id = ?", Integer.class, uid));
    }

    // ---- brief 用例 2：partial 按未完成比例退款 ----

    /** §4.3 partial（终态）→ 按未完成条数比例退一次：refundN = charged×(100-progress)/100。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void partialTaskRefundsUnfinishedProportion() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 20));
        long taskId = analyzeService.startAccount(uid, "ok-url"); // 扣 max(1,min(10,floor(20/2)))=10
        analyzeTaskMapper.markPartial(taskId, 50); // 完成一半
        poller.reconcile();
        assertEquals(5, creditService.balance(uid)); // 退未完成的一半
        // 承重：退款流水恰好 1 条（refund 真正落库，非 balance 假绿）。
        assertEquals(1, refundCount("analyze_account"));
    }

    // ---- 补充承重用例：轮询三态 + 幂等 + 同步视频 ----

    /** §4.3 running-timeout：running 且 updated_at 超 5min → 判 failed 全额退。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runningTimeoutFullRefund() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 20));
        long taskId = analyzeService.startAccount(uid, "ok-url"); // 扣 10
        // 模拟 Python 写了 running 但 updated_at 停滞 10 分钟（转写卡死 / 进程崩溃）
        jdbcTemplate.update(
                "UPDATE analyze_task SET status = 'running', updated_at = now() - interval '10 min' WHERE id = ?",
                taskId);
        poller.reconcile();
        assertEquals(10, creditService.balance(uid)); // 全额退
        assertEquals(1, refundCount("analyze_account"));
        assertEquals(1, failedCount("account"));
    }

    /** §4.3 stale-queued：queued 且 updated_at 超 1min（Python 返回 202 后即崩）→ 判 failed 全额退。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleQueuedFullRefund() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 20));
        long taskId = analyzeService.startAccount(uid, "ok-url"); // 扣 10
        // Python 返回 202 后崩溃，未写 running，1 分钟后 Java 判停滞
        jdbcTemplate.update(
                "UPDATE analyze_task SET status = 'queued', updated_at = now() - interval '2 min' WHERE id = ?",
                taskId);
        poller.reconcile();
        assertEquals(10, creditService.balance(uid)); // 全额退
        assertEquals(1, refundCount("analyze_account"));
        assertEquals(1, failedCount("account"));
    }

    /** §4.3 幂等：partial 重复 reconcile 不双退（credit_ledger 唯一约束 + count 守卫）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void idempotentReconcileDoesNotDoubleRefund() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 20));
        long taskId = analyzeService.startAccount(uid, "ok-url"); // 扣 10
        analyzeTaskMapper.markPartial(taskId, 50);
        poller.reconcile();
        poller.reconcile(); // 再跑一次——不应双退
        assertEquals(5, creditService.balance(uid)); // 仍退 5，未双退
        assertEquals(1, refundCount("analyze_account")); // 恰 1 条退款流水
    }

    /** §4.3 video/text 同步成功：扣 1，task=done，result 回填。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void videoTextSyncChargesOnSuccess() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.analyzeVideoText(anyLong(), any()))
                .thenReturn(
                        new AiClient.VideoTextResult(
                                false, "结构", "原因", "框架", "提示"));
        AiClient.VideoTextResult r = analyzeService.startVideoText(uid, "转写文案");
        assertEquals(4, creditService.balance(uid)); // 扣 1
        assertFalse(r.blocked());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM analyze_task WHERE user_id = ? AND status = 'done' AND task_type = 'video'",
                        Integer.class,
                        uid));
        assertEquals(0, refundCount("analyze_video")); // 成功不退
    }

    /** §4.3 video/text 同步失败（HTTP 超时）→ failed + 退 1 + 抛 AI_FAILED。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void videoTextSyncRefundsOnFailure() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.analyzeVideoText(anyLong(), any())).thenThrow(new RuntimeException("timeout"));
        assertThrows(BizException.class, () -> analyzeService.startVideoText(uid, "转写文案"));
        assertEquals(5, creditService.balance(uid)); // 退回
        assertEquals(1, refundCount("analyze_video"));
        assertEquals(1, failedCount("video"));
    }

    /** §4.3 video/text 命中安全（blocked）→ failed + 退 1 + 抛 CONTENT_BLOCKED。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void videoTextSyncRefundsOnBlocked() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.analyzeVideoText(anyLong(), any()))
                .thenReturn(new AiClient.VideoTextResult(true, null, null, null, null));
        BizException e =
                assertThrows(BizException.class, () -> analyzeService.startVideoText(uid, "转写文案"));
        assertEquals(com.sks.common.ErrorCode.CONTENT_BLOCKED, e.errorCode());
        assertEquals(5, creditService.balance(uid)); // 退回
        assertEquals(1, refundCount("analyze_video"));
        assertEquals(1, failedCount("video"));
    }

    /** §4.3 video/link 异步：扣 1 → 建 queued → 调 Python 202 → 返回 taskId。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void videoLinkStartsAsyncTask() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.analyzeVideoLink(anyLong(), any())).thenReturn(new AiClient.AnalyzeAccepted(0));
        long taskId = analyzeService.startVideoLink(uid, "https://v.douyin.com/xxx");
        assertEquals(4, creditService.balance(uid)); // 扣 1
        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM analyze_task WHERE id = ?", String.class, taskId);
        assertEquals("queued", status);
    }

    /** §4.3 account done → 规律归纳中的视频选题写入 topic(source=benchmark)，幂等不双插。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accountDoneWritesBenchmarkTopics() {
        creditService.credit(uid, 10, "recharge", "o1", null);
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 4)); // charge=2
        long taskId = analyzeService.startAccount(uid, "ok-url");
        // 模拟 Python 写 done + 三层 result（含 videos 列表）
        String resultJson =
                "{\"account_profile\":\"账号画像\",\"patterns\":\"规律归纳\",\"migration_advice\":\"迁移建议\","
                        + "\"videos\":[{\"title\":\"爆款1\",\"play_count\":1000,\"fav_count\":50},"
                        + "{\"title\":\"爆款2\",\"play_count\":2000,\"fav_count\":80}]}";
        jdbcTemplate.update(
                "UPDATE analyze_task SET status = 'done', progress = 100, result = ?::jsonb, updated_at = now() WHERE id = ?",
                resultJson,
                taskId);
        poller.reconcile();
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM topic WHERE user_id = ? AND source = 'benchmark'",
                        Integer.class,
                        uid));
        // 再 reconcile 不双插（按 user_id+source+title 守卫）
        poller.reconcile();
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM topic WHERE user_id = ? AND source = 'benchmark'",
                        Integer.class,
                        uid));
    }

    /** §4.3 account 额度公式：charge = max(1, min(10, floor(N/2)))。N=4→2, N=20→10, N=1→1。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accountChargeFormula() {
        creditService.credit(uid, 100, "recharge", "o1", null);
        when(aiClient.analyzeVideoLink(anyLong(), any())).thenReturn(new AiClient.AnalyzeAccepted(0));
        // N=4 → floor(4/2)=2
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 4));
        analyzeService.startAccount(uid, "u1");
        assertEquals(98, creditService.balance(uid));
        // N=20 → min(10, 10)=10
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 20));
        analyzeService.startAccount(uid, "u2");
        assertEquals(88, creditService.balance(uid));
        // N=1 → floor(1/2)=0 → max(1, 0)=1（下限 1 防白嫖）
        when(aiClient.precheck(any())).thenReturn(new AiClient.Precheck(true, 1));
        analyzeService.startAccount(uid, "u3");
        assertEquals(87, creditService.balance(uid));
    }
}
