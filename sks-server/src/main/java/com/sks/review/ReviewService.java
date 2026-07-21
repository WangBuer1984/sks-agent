package com.sks.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.kb.KbCardService;
import com.sks.script.Script;
import com.sks.script.ScriptMapper;
import com.sks.topic.TopicService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 复盘编排服务（§4.4）——把纯函数 {@link ReviewStateMachine} 的判态结果落到 DB，并编排副作用。
 *
 * <p><b>no AI judges state</b>（CLAUDE.md 硬不变量）：状态迁移<b>全部</b>由纯函数 {@link ReviewStateMachine#next}
 * 决定（静态迁移表 + 双阈值带状数学），唯一的 LLM 调用 {@link AiClient#attributionSingle}（flop 归因）
 * <b>只返回诊断文本、不改态</b>——可证伪「LLM 影响态」的回归。
 *
 * <p><b>复盘是 FREE</b>（不扣额度）：adopt / track / play / attribute / feedback 全程不触
 * {@link com.sks.credit.CreditService} / 不写 credit_ledger。故事务边界比 §4.1 轻量——但仍守 §4.1 教训：
 * hot 副作用的 cardGen HTTP 调用在<b>事务外</b>（30-60s 长调用不持连接），写卡 / 写续集选题各走独立
 * {@link KbCardService#create} / {@link TopicService#create}（各自独立短事务，单卡失败不影响其他卡）。
 *
 * <p><b>副作用（态已定后编排，不改态）：</b>
 * <ul>
 *   <li><b>hot</b>：{@link AiClient#cardGen}(targetLayer="C") 抽 C 层卡 → {@link KbCardService#create}
 *       落库（爆款素材→C 层卡）；{@link TopicService#create}(source="replay") 写续集选题。best-effort——
 *       cardGen / 单卡 / 选题失败 → log 降级，态仍为 hot（review 免费，不应让副作用失败回退已定的态）。
 *   <li><b>flop</b>：{@link #attribute} 调 {@link AiClient#attributionSingle} 返回诊断 / 建议（FREE），
 *       blocked → {@link ErrorCode#CONTENT_BLOCKED}（不扣费，无副作用）。
 *   <li><b>rejected</b>：{@link #feedback} 回访反哺 → 写一条 {@code source="replay"} 选题（title=safetyCheck
 *       过的 feedback 摘要，rationale=完整 feedback）。简单且 spec-compliant（"反哺选题"）。
 * </ul>
 *
 * <p><b>IDOR 防护</b>（§5.1）：所有端点的 {@code scriptId} 经 {@link ScriptMapper#findById} 带 user_id 过滤，
 * 跨用户 → null → {@link ErrorCode#PARAM_INVALID}（不泄露「存在但不属于你」）。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ScriptMapper scriptMapper;
    private final AiClient aiClient;
    private final KbCardService kbCardService;
    private final TopicService topicService;
    private final double hotThreshold;
    private final double flopThreshold;
    private final TransactionTemplate tx;

    public ReviewService(
            ScriptMapper scriptMapper,
            AiClient aiClient,
            KbCardService kbCardService,
            TopicService topicService,
            @Value("${sks.review.hot-threshold:3.0}") double hotThreshold,
            @Value("${sks.review.flop-threshold:0.5}") double flopThreshold,
            PlatformTransactionManager transactionManager) {
        this.scriptMapper = scriptMapper;
        this.aiClient = aiClient;
        this.kbCardService = kbCardService;
        this.topicService = topicService;
        this.hotThreshold = hotThreshold;
        this.flopThreshold = flopThreshold;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 采用稿件：{@code draft → pending}。IDOR：跨用户稿件 → PARAM_INVALID。
     * 非法态（非 draft）→ 状态机抛 {@link IllegalStateException}，翻译为 {@link ErrorCode#PARAM_INVALID}。
     */
    public void adopt(long userId, long scriptId) {
        Script s = load(userId, scriptId);
        String next = transition(s.getReviewState(), ReviewEvent.ADOPT, null);
        int rows = scriptMapper.markPending(scriptId, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件状态已变更，请刷新");
        }
        // next 仅用于校验合法性，实际落库由 markPending（守卫 review_state='draft'）
        assert next != null;
    }

    /**
     * 登记发布链接：{@code pending → tracking} + 写 publish_url。IDOR。url 空白 → PARAM_INVALID。
     * 非法态 → PARAM_INVALID。
     */
    public void track(long userId, long scriptId, String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "发布链接不能为空");
        }
        Script s = load(userId, scriptId);
        transition(s.getReviewState(), ReviewEvent.TRACK, null);
        int rows = scriptMapper.markTracking(scriptId, userId, url);
        if (rows == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件状态已变更，请刷新");
        }
    }

    /**
     * 填播放量：{@code tracking → classify → hot/plain/flop} + 写 play_count（data_source=manual）。
     *
     * <p>先查近 30 天均值（baseline）→ 纯函数 classify 判态 → 落库。hot 副作用（cardGen C + 续集选题）
     * best-effort，在态已落库后编排——HTTP 在事务外、写卡 / 写选题各走独立短事务；任何副作用失败
     * log 降级，态仍为 hot（review 免费，不让副作用失败回退已定的态）。
     *
     * @return 判定后的复盘态（hot/plain/flop），供前端展示
     */
    public String play(long userId, long scriptId, int playCount) {
        if (playCount < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "播放量不能为负");
        }
        Script s = load(userId, scriptId);
        double avg = scriptMapper.avgPlayCount30d(userId);
        ReviewContext ctx = new ReviewContext(playCount, avg, hotThreshold, flopThreshold);
        String next = transition(s.getReviewState(), ReviewEvent.PLAY_COUNT, ctx);
        int rows = scriptMapper.markReviewState(scriptId, userId, next, playCount);
        if (rows == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件状态已变更，请刷新");
        }
        if (ReviewStateMachine.HOT.equals(next)) {
            // best-effort 副作用：态已落库为 hot，副作用失败不回退态
            applyHotSideEffects(userId, s);
        }
        return next;
    }

    /**
     * 调 {@link ReviewStateMachine#next}，把非法迁移的 {@link IllegalStateException} 翻译为
     * {@link ErrorCode#PARAM_INVALID}（用户可见的业务异常，而非裸 500）。
     */
    private String transition(String current, ReviewEvent event, ReviewContext ctx) {
        try {
            return ReviewStateMachine.next(current, event, ctx);
        } catch (IllegalStateException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }

    /**
     * 看归因（flop → 调 {@link AiClient#attributionSingle} 返回诊断 / 建议）。<b>FREE</b>（不扣额度）。
     *
     * <p>仅 flop 态可调；非 flop → PARAM_INVALID。blocked → {@link ErrorCode#CONTENT_BLOCKED}（不扣费、无副作用）。
     * 归因<b>不改态</b>——flop 仍是 flop，只返回诊断文本（no AI judges state）。
     */
    public AttributionView attribute(long userId, long scriptId) {
        Script s = load(userId, scriptId);
        if (!ReviewStateMachine.FLOP.equals(s.getReviewState())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅扑街稿件可看归因");
        }
        double baseline = scriptMapper.avgPlayCount30d(userId);
        int playCount = s.getPlayCount() == null ? 0 : s.getPlayCount();
        AiClient.AttributionSingleResult r =
                aiClient.attributionSingle(scriptText(s), playCount, baseline);
        if (r.blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        return new AttributionView(
                r.diagnosis(), r.suggestions() == null ? List.of() : r.suggestions());
    }

    /**
     * rejected 回访反哺：写一条 {@code source="replay"} 选题（title=safetyCheck 过的 feedback 摘要，
     * rationale=完整 feedback）。
     *
     * <p>简单且 spec-compliant（"反哺选题/口吻偏好"）——复用 {@link TopicService#create}（已含 title safetyCheck）。
     * feedback 空白 → PARAM_INVALID。<b>不限态</b>：brief 未要求态约束（rejected 是典型场景但非唯一），
     * 任何态的稿件都可回访反哺。归因 FREE，无额度链路。
     */
    public void feedback(long userId, long scriptId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "反馈不能为空");
        }
        load(userId, scriptId); // IDOR 校验
        String title = reason.length() > 50 ? reason.substring(0, 50) : reason;
        topicService.create(userId, title, reason, "replay");
    }

    // ---- 内部 ----

    private Script load(long userId, long scriptId) {
        Script s = scriptMapper.findById(scriptId, userId);
        if (s == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件不存在");
        }
        return s;
    }

    /**
     * hot 副作用：cardGen C 层卡（爆款素材→C 层卡）+ 续集选题（source=replay）。best-effort。
     *
     * <p>cardGen HTTP 调用在<b>事务外</b>（§4.1 教训：长调用不持连接）；写卡各走 {@link KbCardService#create}
     * 独立短事务（单卡 safetyCheck 失败不影响其他卡、不回退已定的 hot 态）。blocked / HTTP 失败 → log 降级。
     * 续集选题独立 {@link TopicService#create}（safetyCheck title），失败 log 降级。
     */
    private void applyHotSideEffects(long userId, Script s) {
        String scriptText = scriptText(s);
        AiClient.CardGenResult cardResult;
        try {
            cardResult = aiClient.cardGen(userId, scriptText, "C");
        } catch (RuntimeException e) {
            log.warn("hot side-effect cardGen failed for script {}, degrading (state stays hot)", s.getId(), e);
            return;
        }
        if (cardResult.blocked()) {
            log.warn("hot side-effect cardGen blocked for script {}, degrading", s.getId());
            return;
        }
        List<AiClient.CardGenCard> cards = cardResult.cards() == null ? List.of() : cardResult.cards();
        // 写卡：每张独立 KbCardService.create（独立短事务），单卡失败 catch 降级，不影响其他卡 / 不回退态
        for (AiClient.CardGenCard c : cards) {
            try {
                kbCardService.create(userId, "C", c.cardType(), c.title(), c.content().toString());
            } catch (RuntimeException e) {
                log.warn("hot side-effect: one C-card create failed for script {}, skipping", s.getId(), e);
            }
        }
        // 续集选题：title 取首句 + "（续）"，rationale 用稿件前 100 字。失败 log 降级。
        try {
            String title = deriveReplayTitle(s);
            String rationale = "爆款续集:" + scriptText.substring(0, Math.min(100, scriptText.length()));
            topicService.create(userId, title, rationale, "replay");
        } catch (RuntimeException e) {
            log.warn("hot side-effect replay topic failed for script {}, degrading", s.getId(), e);
        }
    }

    /** 续集选题 title：取正文首句 + "（续）"；无则 "爆款续集"。自包含，无额外 DB 调用。 */
    private String deriveReplayTitle(Script s) {
        String first = s.bodySentence(0);
        if (first != null && !first.isBlank()) {
            return (first.length() > 40 ? first.substring(0, 40) : first) + "（续）";
        }
        return "爆款续集";
    }

    /**
     * 扁平化稿件为纯文本：解析 hook/body/cta 的 {@code {sentences:[{idx,text}]}} JSON，拼接所有 text。
     * 供 cardGen（抽 C 层卡原料）与 attributionSingle（归因输入）用。与 {@link Script#sentence} 同解析口径。
     */
    static String scriptText(Script s) {
        StringBuilder sb = new StringBuilder();
        appendTexts(sb, s.getHook());
        appendTexts(sb, s.getBody());
        appendTexts(sb, s.getCta());
        return sb.toString();
    }

    private static void appendTexts(StringBuilder sb, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode root = OM.readTree(json);
            JsonNode sentences = root.path("sentences");
            if (sentences.isArray()) {
                for (JsonNode sn : sentences) {
                    String t = sn.path("text").asText("");
                    if (!t.isEmpty()) {
                        sb.append(t).append(' ');
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败忽略——返回已拼接部分
        }
    }

    /** flop 归因视图：诊断 + 建议列表。 */
    public record AttributionView(String diagnosis, List<String> suggestions) {}
}
