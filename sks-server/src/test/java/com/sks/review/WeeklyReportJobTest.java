package com.sks.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.script.ScriptMapper;
import com.sks.topic.TopicService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WeeklyReportJob} 集成测试（§4.4 周归因定时任务）——聚合各用户该周已复盘稿 → 调
 * {@link AiClient#attributionWeekly} → upsert {@code weekly_report}。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2）。{@link AiClient} 用 {@code @MockBean}
 * mock——不真正调 Python。job 写的是<b>已提交</b>行（{@code weekly_report} UNIQUE(user_id, week_start) backstop），
 * 故测试标 {@code NOT_SUPPORTED} 让 job 的独立短事务提交可见，{@code cleanup} 显式清理。
 *
 * <p><b>承重断言：</b>
 * <ul>
 *   <li>{@link #runWeeklyReportWithScriptsPersistsFourSections}：一周有已复盘稿 → 跑 job →
 *       {@code weekly_report} 恰 1 行，{@code content} 含 summary/wins/gaps/next_focus 四段；
 *       scripts 数组形状对齐 Task 4.1 {@code WeeklyScriptItem}（script/play_count/review_state/baseline）。
 *   <li>{@link #runWeeklyReportIdempotentUpsert}：跑两次仍 1 行（UNIQUE backstop + upsert 覆盖 content）。
 *   <li>{@link #runWeeklyReportNoScriptsSkips}：用户该周无已复盘稿 → 不写行（不产空报告）。
 *   <li>{@link #runWeeklyReportBlockedWritesBlockedContent}：attributionWeekly 返回 {@code blocked:true}
 *       → 写一行 content 标注 blocked（不产四段空报告）。
 * </ul>
 *
 * <p><b>HTTP 在事务外</b>（§4.1 教训）：job 本身非 {@code @Transactional}——aggregate（读）→
 * attributionWeekly HTTP（30-60s，不持连接）→ 短 tx upsert。本测试 mock 了 HTTP 调用，仍守此结构。
 */
class WeeklyReportJobTest extends AbstractDbTest {

    /** 固定的 ISO 周起始（Monday）——避免依赖 wall-clock，断言可复现。 */
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13);
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired WeeklyReportJob weeklyReportJob;
    @Autowired ScriptMapper scriptMapper;
    @Autowired TopicService topicService;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean AiClient aiClient;

    private long uid;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        AppUser u = new AppUser();
        u.setPhone("13900000430");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        // FK 安全顺序：weekly_report → card_citation → kb_card → script → topic → app_user
        List<Long> userIds =
                jdbcTemplate.queryForList(
                        "SELECT id FROM app_user WHERE phone IN ('13900000430','13900000431')",
                        Long.class);
        if (userIds.isEmpty()) {
            return;
        }
        String in = String.join(",", userIds.stream().map(String::valueOf).toList());
        jdbcTemplate.update("DELETE FROM weekly_report WHERE user_id IN (" + in + ")");
        jdbcTemplate.update(
                "DELETE FROM card_citation WHERE script_id IN (SELECT id FROM script WHERE user_id IN ("
                        + in + "))");
        jdbcTemplate.update("DELETE FROM kb_card WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM script WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM topic WHERE user_id IN (" + in + ")");
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (" + in + ")");
    }

    /** 一周有已复盘稿 → job 跑 → weekly_report 1 行 + content 四段；scripts 形状对齐 Task 4.1。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runWeeklyReportWithScriptsPersistsFourSections() {
        insertFinalizedScriptInWeek("hot", 9000, WEEK_START.plusDays(1));
        insertFinalizedScriptInWeek("flop", 500, WEEK_START.plusDays(2));
        when(aiClient.attributionWeekly(anyLong(), any()))
                .thenReturn(new AiClient.AttributionWeeklyResult(
                        "本周爆款集中在强钩子开头",
                        List.of("开头悬念"),
                        List.of("结尾 CTA 偏弱"),
                        "稳定开头节奏",
                        false));

        weeklyReportJob.runWeeklyReport(WEEK_START);

        // weekly_report 恰 1 行
        int rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        Integer.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        assertEquals(1, rows);

        // content 含四段
        String contentJson =
                jdbcTemplate.queryForObject(
                        "SELECT content FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        String.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        JsonNode content = assertParse(contentJson);
        assertEquals("本周爆款集中在强钩子开头", content.path("summary").asText());
        assertTrue(content.path("wins").isArray() && content.path("wins").size() == 1);
        assertTrue(content.path("gaps").isArray() && content.path("gaps").size() == 1);
        assertEquals("稳定开头节奏", content.path("next_focus").asText());

        // scripts 数组形状对齐 Task 4.1 WeeklyScriptItem（script/play_count/review_state/baseline）
        verify(aiClient)
                .attributionWeekly(
                        eq(uid),
                        argThat(
                                (List<Map<String, Object>> scripts) -> {
                                    if (scripts.size() != 2) {
                                        return false;
                                    }
                                    for (Map<String, Object> s : scripts) {
                                        if (!s.containsKey("script")
                                                || !s.containsKey("play_count")
                                                || !s.containsKey("review_state")
                                                || !s.containsKey("baseline")) {
                                            return false;
                                        }
                                    }
                                    return true;
                                }));
    }

    /** job 幂等：跑两次仍 1 行（UNIQUE backstop + upsert 覆盖 content）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runWeeklyReportIdempotentUpsert() {
        insertFinalizedScriptInWeek("plain", 12345, WEEK_START.plusDays(1));
        when(aiClient.attributionWeekly(anyLong(), any()))
                .thenReturn(
                        new AiClient.AttributionWeeklyResult(
                                "s1", List.of("w1"), List.of("g1"), "f1", false));

        weeklyReportJob.runWeeklyReport(WEEK_START);
        // 第二次返回不同内容，断言 upsert 覆盖（而非插新行）
        when(aiClient.attributionWeekly(anyLong(), any()))
                .thenReturn(
                        new AiClient.AttributionWeeklyResult(
                                "s2", List.of("w2"), List.of("g2"), "f2", false));
        weeklyReportJob.runWeeklyReport(WEEK_START);

        int rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        Integer.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        assertEquals(1, rows);
        String contentJson =
                jdbcTemplate.queryForObject(
                        "SELECT content FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        String.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        JsonNode content = assertParse(contentJson);
        assertEquals("s2", content.path("summary").asText(), "upsert 应覆盖 content");
    }

    /** 用户该周无已复盘稿 → 不写行（不产空报告）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runWeeklyReportNoScriptsSkips() {
        weeklyReportJob.runWeeklyReport(WEEK_START);
        int cnt =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        Integer.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        assertEquals(0, cnt);
        verify(aiClient, org.mockito.Mockito.never()).attributionWeekly(anyLong(), any());
    }

    /** attributionWeekly 返回 blocked:true → 写一行 content 标注 blocked（不产四段空报告）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runWeeklyReportBlockedWritesBlockedContent() {
        insertFinalizedScriptInWeek("flop", 500, WEEK_START.plusDays(1));
        when(aiClient.attributionWeekly(anyLong(), any()))
                .thenReturn(
                        new AiClient.AttributionWeeklyResult(null, List.of(), List.of(), null, true));

        weeklyReportJob.runWeeklyReport(WEEK_START);

        int rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        Integer.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        assertEquals(1, rows);
        String contentJson =
                jdbcTemplate.queryForObject(
                        "SELECT content FROM weekly_report WHERE user_id = ? AND week_start = ?",
                        String.class,
                        uid,
                        java.sql.Date.valueOf(WEEK_START));
        JsonNode content = assertParse(contentJson);
        assertTrue(content.path("blocked").asBoolean(false), "content 应标注 blocked=true");
    }

    // ---- helpers ----

    /** 在指定周的某天插一条已复盘稿（hot/plain/flop），带 play_count + hook/body/cta。 */
    private void insertFinalizedScriptInWeek(String state, int playCount, LocalDate dayInWeek) {
        long topicId = topicService.create(uid, "周归因选题", "本周选题", "faq");
        jdbcTemplate.update(
                "INSERT INTO script(user_id, topic_id, platform, review_state, play_count, "
                        + "data_source, hook, body, cta, created_at) "
                        + "VALUES(?, ?, 'douyin', ?, ?, 'manual', ?::jsonb, ?::jsonb, ?::jsonb, ?::timestamp)",
                uid,
                topicId,
                state,
                playCount,
                section("钩子开头").toString(),
                section("正文内容。").toString(),
                section("结尾 CTA。").toString(),
                java.sql.Timestamp.valueOf(dayInWeek.atStartOfDay()));
    }

    private static JsonNode assertParse(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("content 不是合法 JSON: " + json, e);
        }
    }

    private static JsonNode section(String text) {
        ObjectNode root = OM.createObjectNode();
        ObjectNode s = root.putArray("sentences").addObject();
        s.put("idx", 0);
        s.put("text", text);
        return root;
    }
}
