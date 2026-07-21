package com.sks.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.aiclient.AiClient;
import com.sks.script.Script;
import com.sks.script.ScriptMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 周归因定时任务（§4.4）——每周日 02:30 跑，聚合各用户<b>上一周</b>（ISO 周 Mon-Sun）已复盘稿 → 调
 * {@link AiClient#attributionWeekly}（Task 4.1 Python 端点）→ upsert {@code weekly_report}。
 *
 * <p><b>week_start 约定</b>：ISO 周一为周首。job 跑时（周日 02:30）取<b>刚结束的这周</b>的 Monday 作
 * {@code week_start}——即「今天（周日）所在 ISO 周的 Monday」（{@code today.minusDays(today.dayOfWeek-1)}，
 * 周日=7 → 减 6 天 = 本周一）。覆盖范围 {@code [week_start, week_start+7d)}（半开区间，含周一到周日
 * 截至跑点）。任务可重跑：{@link WeeklyReportMapper#upsert} 靠 UNIQUE(user_id, week_start) backstop 幂等。
 *
 * <p><b>周归因 FREE</b>（不扣额度）：是定时 read-aggregate-LLM-write，非用户扣费路径——
 * 不触 {@link com.sks.credit.CreditService} / 不写 credit_ledger（与 {@link ReviewService#attribute} 同档）。
 *
 * <p><b>HTTP 在事务外</b>（§4.1 教训，mirror {@code §4.3 confirm} 模式）：job 本身<b>非</b>
 * {@code @Transactional}——三段：
 * <ol>
 *   <li>aggregate（读）：{@link ScriptMapper#findUserIdsWithFinalizedScriptsInWeek} +
 *       {@link ScriptMapper#findFinalizedByUserAndWeek} 取该周已复盘稿（hot/plain/flop，含 play_count）。
 *   <li>HTTP（事务外）：{@link AiClient#attributionWeekly}（30-60s+，不持 DB 连接）。
 *   <li>短 tx 写：{@link WeeklyReportMapper#upsert}（独立短事务，UNIQUE backstop 幂等）。
 * </ol>
 *
 * <p><b>无 Redis/MQ</b>（CLAUDE.md）：用 Postgres + {@code @Scheduled}，与 {@link com.sks.topic.HotTopicJob}
 * / {@link com.sks.analyze.AnalyzeTaskPoller} / {@link RejectSweeper} 同模式。per-user try/catch：单用户
 * 失败不波及其他用户、不中断调度。
 *
 * <p><b>scripts 数组形状</b>（对齐 Task 4.1 {@code WeeklyScriptItem}）：每条 {@code Map} 含
 * {@code script}（hook/body/cta flatten 纯文本）、{@code play_count}、{@code review_state}、{@code baseline}
 * （用户近 30 天均值）。Python 放行额外字段，仅取已知字段。
 *
 * <p><b>blocked 处理</b>：{@code attributionWeekly} 返回 {@code blocked:true}（内容安全命中）→ 写一行
 * {@code content={"blocked":true,"summary":null,...}}，不产四段空报告（让前端知道本周归因被安全拦截，
 * 而非「无报告」）。
 *
 * <p><b>无流式</b>（硬不变量）：job 写 JSONB，{@code GET /api/review/weekly} 返回一个 JSON。
 */
@Component
public class WeeklyReportJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportJob.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** 周日 02:30 跑（避开 :00/:30 整点高峰）。固定 cron——MVP 不做配置化（YAGNI）。 */
    static final String WEEKLY_CRON = "0 30 2 * * SUN";

    private final ScriptMapper scriptMapper;
    private final AiClient aiClient;
    private final WeeklyReportMapper weeklyReportMapper;
    private final TransactionTemplate tx;

    public WeeklyReportJob(
            ScriptMapper scriptMapper,
            AiClient aiClient,
            WeeklyReportMapper weeklyReportMapper,
            PlatformTransactionManager transactionManager) {
        this.scriptMapper = scriptMapper;
        this.aiClient = aiClient;
        this.weeklyReportMapper = weeklyReportMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 每周日 02:30 跑：聚合<b>刚结束的这周</b>（ISO 周，week_start = 本周一）的已复盘稿 → 周归因 → upsert。
     *
     * <p>整 Job 任何异常都不向上抛（per-user try/catch + 顶层 try/catch，避免 {@code @Scheduled}
     * 默认吞异常后静默停调度）。
     */
    @Scheduled(cron = WEEKLY_CRON)
    public void sweep() {
        LocalDate today = LocalDate.now();
        // 周日=7，ISO 周一为周首：本周一 = today - (today.dayOfWeek - 1) 天
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        runWeeklyReport(weekStart);
    }

    /**
     * 跑某周的周归因（包给测试 + 重跑用）。遍历该周有已复盘稿的用户，各自 aggregate → HTTP → upsert。
     *
     * <p>per-user try/catch：单用户 HTTP 失败 / upsert 失败只记 WARN，不中断其他用户。
     */
    public void runWeeklyReport(LocalDate weekStart) {
        List<Long> userIds;
        try {
            userIds = scriptMapper.findUserIdsWithFinalizedScriptsInWeek(weekStart);
        } catch (Exception e) {
            log.warn("WeeklyReportJob: failed to list users for week {}: {}", weekStart, e.getMessage());
            return;
        }
        if (userIds.isEmpty()) {
            log.debug("WeeklyReportJob tick: no users with finalized scripts for week {}", weekStart);
            return;
        }
        log.info("WeeklyReportJob tick: aggregating week {} for {} users", weekStart, userIds.size());
        int written = 0;
        for (Long uid : userIds) {
            try {
                runForUser(uid, weekStart);
                written++;
            } catch (Exception e) {
                log.warn(
                        "WeeklyReportJob: weekly attribution failed for user {} week {}: {}",
                        uid,
                        weekStart,
                        e.getMessage());
            }
        }
        log.info(
                "WeeklyReportJob tick done: {} weekly reports written for week {}",
                written,
                weekStart);
    }

    /** 单用户的周归因：aggregate → HTTP（事务外）→ 短 tx upsert。 */
    private void runForUser(long userId, LocalDate weekStart) {
        // 1) aggregate（读，无 tx）
        List<Script> scripts = scriptMapper.findFinalizedByUserAndWeek(userId, weekStart);
        if (scripts.isEmpty()) {
            return; // 无已复盘稿 → 跳过（不产空报告）
        }
        double baseline = scriptMapper.avgPlayCount30d(userId);
        List<Map<String, Object>> scriptsPayload = new ArrayList<>(scripts.size());
        for (Script s : scripts) {
            Map<String, Object> item = new HashMap<>();
            item.put("script", ReviewService.scriptText(s));
            item.put("play_count", s.getPlayCount() == null ? 0 : s.getPlayCount());
            item.put("review_state", s.getReviewState() == null ? "unknown" : s.getReviewState());
            item.put("baseline", baseline);
            scriptsPayload.add(item);
        }
        // 2) HTTP（事务外，30-60s+ 不持连接）
        AiClient.AttributionWeeklyResult r = aiClient.attributionWeekly(userId, scriptsPayload);
        String contentJson = r.blocked() ? blockedContent() : fourSectionContent(r);
        // 3) 短 tx upsert（UNIQUE backstop 幂等）
        tx.executeWithoutResult(status -> weeklyReportMapper.upsert(userId, weekStart, contentJson));
    }

    /** 组装四段 content：{@code {summary, wins, gaps, next_focus}}。 */
    private static String fourSectionContent(AiClient.AttributionWeeklyResult r) {
        ObjectNode node = OM.createObjectNode();
        node.put("summary", r.summary());
        node.set("wins", OM.valueToTree(r.wins() == null ? List.of() : r.wins()));
        node.set("gaps", OM.valueToTree(r.gaps() == null ? List.of() : r.gaps()));
        node.put("next_focus", r.nextFocus());
        return node.toString();
    }

    /** blocked content：{@code {blocked:true}}——前端据此区分「被安全拦截」与「无报告」。 */
    private static String blockedContent() {
        ObjectNode node = OM.createObjectNode();
        node.put("blocked", true);
        return node.toString();
    }
}
