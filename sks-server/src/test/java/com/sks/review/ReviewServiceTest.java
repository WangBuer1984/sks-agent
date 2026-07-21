package com.sks.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.kb.KbCardService;
import com.sks.script.Script;
import com.sks.script.ScriptMapper;
import com.sks.topic.Topic;
import com.sks.topic.TopicMapper;
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
 * {@link ReviewService} + {@link RejectSweeper} 集成测试——§4.4 复盘状态机落库 + 副作用 + IDOR + 扫描。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2）。{@link AiClient} 用 {@code @MockBean}
 * mock——不真正调 Python。{@link ReviewStateMachine} 的纯函数判态已由 {@link ReviewStateMachineTest}
 * 覆盖（无 Spring）；本类覆盖「纯函数结果落到 DB + 副作用编排」的集成路径。
 *
 * <p><b>no AI judges state</b>（CLAUDE.md 硬不变量）：本类断言态迁移全部由纯函数决定——
 * {@link #playCountAboveThresholdBecomesHotPersists} 等用 mocked attributionSingle/cardGen 验证
 * 副作用在态已定后编排，不反过来影响态。
 *
 * <p><b>事务隔离</b>：复盘是 FREE（无额度链路），但 hot 副作用的 cardGen HTTP 调用在事务外、写卡各走
 * 独立短事务——本测试标 {@code NOT_SUPPORTED} 让各 service 调用独立提交，{@code cleanup} 显式清理。
 */
class ReviewServiceTest extends AbstractDbTest {

    @Autowired ReviewService reviewService;
    @Autowired RejectSweeper rejectSweeper;
    @Autowired ScriptMapper scriptMapper;
    @Autowired TopicService topicService;
    @Autowired TopicMapper topicMapper;
    @Autowired KbCardService kbCardService;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean AiClient aiClient;

    private static final ObjectMapper OM = new ObjectMapper();

    private long uid;
    private long topicId;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        AppUser u = new AppUser();
        u.setPhone("13900000424");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
        topicId = topicService.create(uid, "口播选题一", "新手选题", "faq");
    }

    @AfterEach
    void cleanup() {
        // 健壮清理：收集所有测试用户（uid + insertOtherUser 建的 13900000999），按 phone 前缀也兜底。
        // FK 安全顺序：card_citation → kb_card → script → topic → app_user。
        List<Long> userIds =
                jdbcTemplate.queryForList(
                        "SELECT id FROM app_user WHERE phone IN ('13900000424','13900000999')",
                        Long.class);
        if (userIds.isEmpty()) {
            return;
        }
        String in = String.join(",", userIds.stream().map(String::valueOf).toList());
        jdbcTemplate.update(
                "DELETE FROM card_citation WHERE script_id IN (SELECT id FROM script WHERE user_id IN ("
                        + in + "))");
        jdbcTemplate.update("DELETE FROM kb_card WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM script WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM topic WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (" + in + ")");
    }

    // ---- play-count → classify 落库（热款 / 平平 / 扑街）----

    /** tracking + 播放量超阈值 → hot 落库 + play_count + data_source=manual。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void playCountAboveThresholdBecomesHotPersists() {
        // 先插一条历史 flop 稿（play_count=2000）建立 baseline，使 avg=2000 → hot 阈值 6000
        insertFinalizedScript("flop", 2000);
        long sid = insertTrackingScript();
        when(aiClient.cardGen(anyLong(), any(), eq("C")))
                .thenReturn(new AiClient.CardGenResult(false, List.of(), List.of(), List.of()));
        String state = reviewService.play(uid, sid, 9000);
        assertEquals("hot", state);
        assertEquals(
                "hot",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
        assertEquals(
                9000,
                jdbcTemplate.queryForObject(
                        "SELECT play_count FROM script WHERE id = ?", Integer.class, sid));
        assertEquals(
                "manual",
                jdbcTemplate.queryForObject(
                        "SELECT data_source FROM script WHERE id = ?", String.class, sid));
    }

    /** tracking + 播放量带内 → plain 落库。无历史 baseline（avg=0）→ plain。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void playCountNoHistoryBecomesPlainPersists() {
        long sid = insertTrackingScript();
        String state = reviewService.play(uid, sid, 12345); // 无历史 avg=0 → plain
        assertEquals("plain", state);
        assertEquals(
                "plain",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
        verify(aiClient, never()).cardGen(anyLong(), any(), any()); // plain 无 hot 副作用
    }

    /** tracking + 播放量低于下界 → flop 落库（有历史 baseline）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void playCountBelowThresholdBecomesFlopPersists() {
        insertFinalizedScript("plain", 2000);
        long sid = insertTrackingScript();
        String state = reviewService.play(uid, sid, 500);
        assertEquals("flop", state);
        assertEquals(
                "flop",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
        verify(aiClient, never()).cardGen(anyLong(), any(), any()); // flop 无 hot 副作用
    }

    // ---- hot 副作用（cardGen C + 续集选题）----

    /** hot → cardGen 返回 1 张卡 → KbCardService.create 落 C 层卡 + 写续集选题（source=replay）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void hotSideEffectsCreateCCardAndReplayTopic() {
        insertFinalizedScript("flop", 2000);
        long sid = insertTrackingScript();
        AiClient.CardGenCard card =
                new AiClient.CardGenCard("quote", "爆款金句", section("爆款素材句"));
        when(aiClient.cardGen(anyLong(), any(), eq("C")))
                .thenReturn(new AiClient.CardGenResult(false, List.of(card), List.of(), List.of()));
        reviewService.play(uid, sid, 9000); // → hot
        // C 层卡落库
        int cCards =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM kb_card WHERE user_id = ? AND layer = 'C'",
                        Integer.class,
                        uid);
        assertEquals(1, cCards);
        // 续集选题落库（source=replay）
        int replayTopics =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM topic WHERE user_id = ? AND source = 'replay'",
                        Integer.class,
                        uid);
        assertTrue(replayTopics >= 1, "续集选题应已落库");
    }

    /** hot → cardGen 抛异常 → 降级 log，态仍为 hot，无卡 / 无续集选题。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void hotSideEffectCardGenFailureDegradesStateStaysHot() {
        insertFinalizedScript("flop", 2000);
        long sid = insertTrackingScript();
        when(aiClient.cardGen(anyLong(), any(), any()))
                .thenThrow(new RuntimeException("ai down"));
        String state = reviewService.play(uid, sid, 9000);
        assertEquals("hot", state); // 态已定，副作用失败不回退
        assertEquals(
                "hot",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
        int cCards =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM kb_card WHERE user_id = ? AND layer = 'C'",
                        Integer.class,
                        uid);
        assertEquals(0, cCards); // 无卡落库
    }

    // ---- flop → 归因（FREE）----

    /** flop → attribute 调 attributionSingle 返回诊断 / 建议，态不变。FREE（无 credit_ledger）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void flopAttributeReturnsDiagnosisFree() {
        insertFinalizedScript("plain", 2000);
        long sid = insertTrackingScript();
        reviewService.play(uid, sid, 500); // → flop
        when(aiClient.attributionSingle(any(), anyInt(), anyDouble()))
                .thenReturn(new AiClient.AttributionSingleResult(
                        "钩子不够抓人", List.of("加强开头悬念"), false));
        ReviewService.AttributionView view = reviewService.attribute(uid, sid);
        assertEquals("钩子不够抓人", view.diagnosis());
        assertEquals(List.of("加强开头悬念"), view.suggestions());
        // FREE：无 credit_ledger 写入
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM credit_ledger WHERE user_id = ?", Integer.class, uid));
        // 态不变
        assertEquals(
                "flop",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    /** flop → attributionSingle 返回 blocked → CONTENT_BLOCKED（不扣费、无副作用）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void flopAttributeBlockedThrowsContentBlocked() {
        insertFinalizedScript("plain", 2000);
        long sid = insertTrackingScript();
        reviewService.play(uid, sid, 500); // → flop
        when(aiClient.attributionSingle(any(), anyInt(), anyDouble()))
                .thenReturn(new AiClient.AttributionSingleResult(null, List.of(), true));
        BizException e =
                assertThrows(BizException.class, () -> reviewService.attribute(uid, sid));
        assertEquals(ErrorCode.CONTENT_BLOCKED, e.errorCode());
    }

    /** 非 flop 态调 attribute → PARAM_INVALID（仅扑街可归因）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void attributeOnNonFlopThrows() {
        long sid = insertTrackingScript();
        // tracking 态调 attribute → 拒绝
        BizException e =
                assertThrows(BizException.class, () -> reviewService.attribute(uid, sid));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        verify(aiClient, never()).attributionSingle(any(), anyInt(), anyDouble());
    }

    // ---- 状态迁移落库 + 非法态拒绝 ----

    /** draft → adopt → pending 落库。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void adoptDraftToPendingPersists() {
        long sid = insertDraftScript();
        reviewService.adopt(uid, sid);
        assertEquals(
                "pending",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    /** pending → track(url) → tracking 落库 + publish_url。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void trackPendingToTrackingPersists() {
        long sid = insertDraftScript();
        reviewService.adopt(uid, sid);
        reviewService.track(uid, sid, "https://v.douyin.com/abc");
        assertEquals(
                "tracking",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
        assertEquals(
                "https://v.douyin.com/abc",
                jdbcTemplate.queryForObject(
                        "SELECT publish_url FROM script WHERE id = ?", String.class, sid));
    }

    /** draft 直接 track（未 adopt）→ PARAM_INVALID（状态机拒绝 draft+TRACK）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void trackOnDraftThrows() {
        long sid = insertDraftScript();
        BizException e =
                assertThrows(BizException.class, () -> reviewService.track(uid, sid, "https://x"));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    /** draft 直接 play（未 tracking）→ PARAM_INVALID（状态机拒绝 draft+PLAY_COUNT，brief 钉死）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void playOnDraftThrows() {
        long sid = insertDraftScript();
        BizException e =
                assertThrows(BizException.class, () -> reviewService.play(uid, sid, 100));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    // ---- IDOR ----

    /** 跨用户 adopt → PARAM_INVALID（不泄露存在性）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void adoptOtherUsersScriptThrowsIdor() {
        long otherUid = insertOtherUser();
        long otherTopic = topicService.create(otherUid, "他人选题", "他人的", "faq");
        long sid = insertDraftScriptFor(otherUid, otherTopic);
        BizException e =
                assertThrows(BizException.class, () -> reviewService.adopt(uid, sid));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        // 原稿仍是 draft
        assertEquals(
                "draft",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    // ---- feedback 反哺 ----

    /** feedback → 写 source=replay 选题（title=safetyCheck 过的 feedback 摘要）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void feedbackWritesReplayTopic() {
        long sid = insertDraftScript();
        reviewService.feedback(uid, sid, "用户觉得开头太长，希望更直接");
        int replay =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM topic WHERE user_id = ? AND source = 'replay'",
                        Integer.class,
                        uid);
        assertEquals(1, replay);
    }

    /** feedback 空白 → PARAM_INVALID。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void feedbackBlankThrows() {
        long sid = insertDraftScript();
        BizException e =
                assertThrows(BizException.class, () -> reviewService.feedback(uid, sid, "  "));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    // ---- RejectSweeper：扫 draft 不扫 pending ----

    /** 49h 未采用的 draft → sweeper 置 rejected。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectSweeperRejectsStaleDraft() {
        long sid = insertDraftScriptOlderThan(49);
        rejectSweeper.sweep();
        assertEquals(
                "rejected",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    /**
     * <b>承重</b>：49h 的 pending → sweeper <b>不扫</b>，仍 pending（brief「注意不能扫 pending」）。
     * pending 是已采用待登记，不是废弃。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectSweeperDoesNotScanPending() {
        long sid = insertPendingScriptOlderThan(49);
        rejectSweeper.sweep();
        assertEquals(
                "pending",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    /** 47h 未采用的 draft → 未超 48h，sweeper 不扫。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectSweeperDoesNotRejectFreshDraft() {
        long sid = insertDraftScriptOlderThan(47);
        rejectSweeper.sweep();
        assertEquals(
                "draft",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    /** sweeper 幂等：重复扫已 rejected 的行 no-op。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectSweeperIdempotent() {
        long sid = insertDraftScriptOlderThan(49);
        rejectSweeper.sweep();
        rejectSweeper.sweep(); // 重复
        assertEquals(
                "rejected",
                jdbcTemplate.queryForObject(
                        "SELECT review_state FROM script WHERE id = ?", String.class, sid));
    }

    // ---- helpers ----

    private long insertOtherUser() {
        AppUser u = new AppUser();
        u.setPhone("13900000999");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        return u.getId();
    }

    /** 插一条已复盘稿（hot/plain/flop）建立 baseline，created_at = now()-5d（在 30 天窗口内）。 */
    private void insertFinalizedScript(String state, int playCount) {
        jdbcTemplate.update(
                "INSERT INTO script(user_id, topic_id, platform, review_state, play_count, "
                        + "data_source, hook, body, cta, created_at) "
                        + "VALUES(?, ?, 'douyin', ?, ?, 'manual', ?::jsonb, ?::jsonb, ?::jsonb, "
                        + "now() - interval '5 days')",
                uid,
                topicId,
                state,
                playCount,
                section("hook").toString(),
                section("body").toString(),
                section("cta").toString());
    }

    private long insertDraftScript() {
        return insertDraftScriptFor(uid, topicId);
    }

    private long insertDraftScriptFor(long userId, long topicForUser) {
        jdbcTemplate.update(
                "INSERT INTO script(user_id, topic_id, platform, review_state, hook, body, cta) "
                        + "VALUES(?, ?, 'douyin', 'draft', ?::jsonb, ?::jsonb, ?::jsonb)",
                userId,
                topicForUser,
                section("钩子").toString(),
                section("正文第一句。").toString(),
                section("结尾。").toString());
        return jdbcTemplate.queryForObject(
                "SELECT max(id) FROM script WHERE user_id = ?", Long.class, userId);
    }

    private long insertTrackingScript() {
        long sid = insertDraftScript();
        reviewService.adopt(uid, sid);
        reviewService.track(uid, sid, "https://v.douyin.com/x");
        return sid;
    }

    private long insertDraftScriptOlderThan(int hours) {
        jdbcTemplate.update(
                "INSERT INTO script(user_id, topic_id, platform, review_state, hook, body, cta, "
                        + "created_at, updated_at) "
                        + "VALUES(?, ?, 'douyin', 'draft', ?::jsonb, ?::jsonb, ?::jsonb, "
                        + "now() - (? || ' hours')::interval, now() - (? || ' hours')::interval)",
                uid,
                topicId,
                section("钩子").toString(),
                section("正文。").toString(),
                section("结尾。").toString(),
                String.valueOf(hours),
                String.valueOf(hours));
        return jdbcTemplate.queryForObject(
                "SELECT max(id) FROM script WHERE user_id = ? AND review_state = 'draft'",
                Long.class,
                uid);
    }

    private long insertPendingScriptOlderThan(int hours) {
        long sid = insertDraftScriptOlderThan(hours);
        jdbcTemplate.update(
                "UPDATE script SET review_state = 'pending' WHERE id = ?", sid);
        return sid;
    }

    private static JsonNode section(String text) {
        ObjectNode root = OM.createObjectNode();
        ObjectNode s = root.putArray("sentences").addObject();
        s.put("idx", 0);
        s.put("text", text);
        return root;
    }
}
