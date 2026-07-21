package com.sks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.credit.CreditService;
import com.sks.topic.TopicService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScriptService} 服务级集成测试——§4.1 额度事务链（产品 #1 资金不变量）。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建 script / topic /
 * card_citation。{@link AiClient} 用 {@code @MockBean} mock——不真正调 Python。
 *
 * <p><b>事务边界 + 测试隔离（§4.1 经典坑，0.7 err_count 教训）：</b>
 *
 * <ol>
 *   <li>{@link ScriptService#generate} 本身<b>不加 {@code @Transactional}</b>；30-60s 的 scriptGen HTTP
 *       调用在任何事务之外；{@link CreditService#deduct} / {@link CreditService#refund} 各自是
 *       {@code @Transactional(REQUIRED)} 独立短事务（从非事务方法调用 → 各自提交）。
 *   <li>基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚）。但本测试<b>必须</b>
 *       标 {@code @Transactional(propagation = NOT_SUPPORTED)} 挂起测试事务：否则 setup credit +
 *       deduct + refund 全部 JOIN 测试事务（未提交）→ 方法结束整体回滚 → {@code balance()} 读到的是
 *       自己未提交的值 → {@code assertEquals(5, balance)} 因「deduct 从未持久化、refund 从未执行」
 *       而<b>假绿</b>（Task 0.7 err_count 掩盖 bug 的同款）。挂起后各次服务调用独立提交到真实 DB，
 *       refund 持久化才被真正证明。
 *   <li>{@link #cleanup} 显式清理已提交行（NOT_SUPPORTED 不随测试事务回滚）——FK 安全顺序：
 *       card_citation → script → credit_ledger → credit_account → topic → app_user。
 * </ol>
 *
 * <p><b>NOT_SUPPORTED 是承重的一行：</b>若 {@link ScriptService#generate} 被误标 {@code @Transactional}，
 * 失败路径的 {@code refund} 会 JOIN 已 rollback-only 的事务 → 不持久化 → {@code balance} 停在 4 →
 * {@code generationFailureRefundsCredit} 的 {@code assertEquals(5, balance)} 红。本测试集因此是
 * 「refund 真正落库」的可证伪断言。
 */
class ScriptServiceTest extends AbstractDbTest {

    @Autowired ScriptService scriptService;
    @Autowired TopicService topicService;
    @Autowired CreditService creditService;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean AiClient aiClient;

    private static final ObjectMapper OM = new ObjectMapper();

    private long uid;
    private long topicId;

    @BeforeEach
    void setup() {
        // Topic 标题走 UGC 安全审核（§5.1）——mock 放行，聚焦本测试的核心：额度事务链。
        when(aiClient.safetyCheck(any())).thenReturn(true);

        AppUser u = new AppUser();
        u.setPhone("13900000014");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();

        topicId = topicService.create(uid, "如何挑选口播选题", "新手最常卡在选题", "faq");
    }

    @AfterEach
    void cleanup() {
        // NOT_SUPPORTED 用例不随测试事务回滚，需显式清理已提交行（FK 安全顺序）。
        // 对回滚型用例为空操作。card_citation.card_id→kb_card；script.topic_id→topic、user_id→app_user。
        jdbcTemplate.update(
                "DELETE FROM card_citation WHERE script_id IN (SELECT id FROM script WHERE user_id = ?)", uid);
        jdbcTemplate.update("DELETE FROM script WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM credit_ledger WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM credit_account WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM topic WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", uid);
    }

    // ---- brief verbatim 用例（3 个，事务隔离标 NOT_SUPPORTED + 清理）----

    /** §4.1：生成失败（HTTP 超时 / 异常）→ 占位行 failed + 退款 + 抛 AI_FAILED。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void generationFailureRefundsCredit() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenThrow(new RuntimeException("timeout"));
        assertThrows(BizException.class, () -> scriptService.generate(uid, topicId, "douyin"));
        assertEquals(5, creditService.balance(uid)); // 已全额退回
        // 承重断言：占位行已持久化为 failed + 退款流水已落库。
        // 若 generate() 被误标 @Transactional，失败路径全在 rollback-only tx 里，两者都会回滚 → 0 → 红。
        // （仅 balance==5 会因「deduct+refund 都回滚净零」而假绿——0.7 掩盖 bug 同款，故加此两条断言。）
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM script WHERE user_id = ? AND review_state = 'failed'",
                        Integer.class,
                        uid));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM credit_ledger WHERE user_id = ? AND biz_type = 'generate' AND type = 'refund'",
                        Integer.class,
                        uid));
    }

    /** §4.2：同选题同平台已有成功稿（非 generating/failed）→ 免扣，返回已有 id，scriptGen 只调一次。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void regenerateSameTopicNotCharged() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        long sid1 = scriptService.generate(uid, topicId, "douyin"); // 扣 1
        long sid2 = scriptService.generate(uid, topicId, "douyin"); // 同平台同选题免扣、不重调
        assertEquals(sid1, sid2); // 返回已有稿 id，不新建
        assertEquals(4, creditService.balance(uid)); // 未再扣
        verify(aiClient, times(1)).scriptGen(any()); // 同平台短路，只生成一次
    }

    /** 单句 AI 重写不扣额度、预览不落库（确认才写）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sentenceRewriteIsFreeAndUpdatesNothingUntilConfirmed() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        long sid = scriptService.generate(uid, topicId, "douyin"); // 扣 1 → 4
        when(aiClient.rewriteSentence(any())).thenReturn("换了说法的新句子");
        String preview = scriptService.rewriteSentence(uid, sid, "body", 0);
        assertEquals(4, creditService.balance(uid)); // 单句重写不扣额度
        assertNotEquals(preview, scriptService.get(sid).bodySentence(0)); // 预览不落库，确认才写
    }

    // ---- 边界用例（cross-task decision 要求的 edge cases）----

    /** §4.1：余额不足 → deduct 抛 INSUFFICIENT_BALANCE → 占位行 failed、不退款（没扣过）、scriptGen 不调。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insufficientBalanceNoRefundAndScriptFailed() {
        // 不充值，余额 0
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        BizException e =
                assertThrows(
                        BizException.class, () -> scriptService.generate(uid, topicId, "douyin"));
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, e.errorCode());
        assertEquals(0, creditService.balance(uid)); // 不退款——没扣过
        verify(aiClient, never()).scriptGen(any()); // 余额不足时根本不该调 Python
        // 占位行存在且为 failed
        Integer failedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM script WHERE user_id = ? AND review_state = 'failed'",
                        Integer.class,
                        uid);
        assertEquals(1, failedCount);
    }

    /** §4.1 / §5.1：Python 返回 {blocked:true} → 占位行 failed + 退款 + 抛 CONTENT_BLOCKED（非 AI_FAILED）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void blockedResultRefundsCreditAndThrowsContentBlocked() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(blockedScriptResult());
        BizException e =
                assertThrows(
                        BizException.class, () -> scriptService.generate(uid, topicId, "douyin"));
        assertEquals(ErrorCode.CONTENT_BLOCKED, e.errorCode());
        assertEquals(5, creditService.balance(uid)); // 已退款
    }

    /**
     * §4.2「三平台版本按需生成…同选题不加扣」（design §3 line 121-122）：切平台再生成同选题→
     * <b>新建</b>新平台稿件、不扣额度、scriptGen 重调一次（省约 2/3 token 的前提是再生成本就发生）。
     *
     * <p>与 {@link #regenerateSameTopicNotCharged} 对照：同平台短路（sid1==sid2、scriptGen×1），
     * 跨平台再生（sid1!=sid2、scriptGen×2、余额不变）。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameTopicNotChargedAcrossPlatforms() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        long sid1 = scriptService.generate(uid, topicId, "douyin"); // 首生成扣 1 → 4
        long sid2 = scriptService.generate(uid, topicId, "kuaishou"); // 切平台再生成，免扣
        assertNotEquals(sid1, sid2); // 新建稿件，非返回旧平台稿
        assertEquals(4, creditService.balance(uid)); // 选题已成功过 → 再生成免费，余额不动
        verify(aiClient, times(2)).scriptGen(any()); // 两次生成都调 Python（再生成本就发生）
    }

    /**
     * §4.1 切平台再生成失败路径：选题已成功过（任意平台）→ 再生成<b>不扣</b>；失败时<b>不退</b>
     * （没扣过，退了就是多给额度），仅占位行置 failed + 抛 AI_FAILED。与 {@link #generationFailureRefundsCredit}
     * （首生成失败必退）形成对照——证伪「再生成失败也退」的回归。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void crossPlatformRegenFailureNoRefund() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        // 首次 douyin 生成成功（扣 1 → 4），第二次 kuaishou 再生成超时
        when(aiClient.scriptGen(any()))
                .thenReturn(okScriptResult())
                .thenThrow(new RuntimeException("timeout"));
        scriptService.generate(uid, topicId, "douyin");
        BizException e =
                assertThrows(
                        BizException.class, () -> scriptService.generate(uid, topicId, "kuaishou"));
        assertEquals(ErrorCode.AI_FAILED, e.errorCode());
        assertEquals(4, creditService.balance(uid)); // 未扣不退——余额停在 4
        // 再生成的占位行已持久化为 failed（首生成那行是 draft，故 failed 计数恰为 1）
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM script WHERE user_id = ? AND review_state = 'failed'",
                        Integer.class,
                        uid));
        // 承重断言：切平台再生成失败<b>不写退款流水</b>（没扣过）。若误走 failAndRefund，此处会变 1 → 红。
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM credit_ledger WHERE user_id = ? AND biz_type = 'generate' AND type = 'refund'",
                        Integer.class,
                        uid));
    }

    /** platform 缺省 → 取 app_user.default_platform（douyin）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void platformDefaultsToUserDefaultPlatform() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        long sid = scriptService.generate(uid, topicId, null); // 缺省 → douyin
        assertEquals(4, creditService.balance(uid));
        String platform =
                jdbcTemplate.queryForObject(
                        "SELECT platform FROM script WHERE id = ?", String.class, sid);
        assertEquals("douyin", platform);
    }

    /** 单句手改（PUT sentence）落库：body[0] 文本被替换。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void editSentencePersists() {
        creditService.credit(uid, 5, "recharge", "o1", null);
        when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
        long sid = scriptService.generate(uid, topicId, "douyin");
        String before = scriptService.get(sid).bodySentence(0);
        scriptService.editSentence(uid, sid, "body", 0, "手改后的句子");
        assertEquals("手改后的句子", scriptService.get(sid).bodySentence(0));
        assertNotEquals(before, scriptService.get(sid).bodySentence(0));
    }

    // ---- helpers ----

    /** 成功生成结果：三段各一句（body[0] 文本固定，便于断言）。 */
    private AiClient.ScriptGenResult okScriptResult() {
        return new AiClient.ScriptGenResult(
                false,
                section("这是开场钩子。"),
                section("这是正文第一句。"),
                section("这是结尾引导。"),
                List.of());
    }

    private static JsonNode section(String text) {
        ObjectNode root = OM.createObjectNode();
        ObjectNode s = root.putArray("sentences").addObject();
        s.put("idx", 0);
        s.put("text", text);
        return root;
    }

    private AiClient.ScriptGenResult blockedScriptResult() {
        return new AiClient.ScriptGenResult(true, null, null, null, List.of());
    }
}
