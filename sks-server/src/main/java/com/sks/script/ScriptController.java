package com.sks.script;

import com.sks.common.ApiResponse;
import com.sks.kb.CardCitationMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端稿件端点 {@code /api/scripts}。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。五个端点：
 *
 * <ul>
 *   <li>{@code POST /generate} —— §4.1 额度事务链（扣费 → 生成 → 失败退回）。返回新稿详情。
 *   <li>{@code GET /?state=} —— 列表（可选 review_state 过滤）。
 *   <li>{@code GET /{id}} —— 详情（含 citedCardIds，供前端右栏「引用卡片」匹配卡片标题）。
 *   <li>{@code PUT /{id}/sentence} —— 单句手改（IDOR：user_id 校验，§5.1）。
 *   <li>{@code POST /{id}/rewrite-sentence} —— 单句 AI 重写预览（不落库、不扣额度；确认走 PUT sentence）。
 * </ul>
 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    private final ScriptService scriptService;
    private final CardCitationMapper cardCitationMapper;

    public ScriptController(ScriptService scriptService, CardCitationMapper cardCitationMapper) {
        this.scriptService = scriptService;
        this.cardCitationMapper = cardCitationMapper;
    }

    /**
     * 生成文案（§4.1 事务链）。platform 缺省取用户主平台。返回新稿详情（含 citedCardIds +
     * {@code dedupWarnScriptId}——命中查重则非空，<b>不阻断</b>，PRD §11.2；前端黄条 + 「换角度」按钮）。
     */
    @PostMapping("/generate")
    public ApiResponse<ScriptDetail> generate(
            @AuthenticationPrincipal Long userId, @RequestBody GenerateRequest req) {
        ScriptService.GenerateResult r = scriptService.generate(userId, req.topicId(), req.platform());
        return ApiResponse.ok(detailOf(userId, r.scriptId(), r.dedupWarnScriptId()));
    }

    /** 稿件列表（可选 review_state 过滤，如 ?state=draft）。不含 hook/body/cta 正文（列表轻量）。 */
    @GetMapping
    public ApiResponse<List<ScriptSummary>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "state", required = false) String state) {
        return ApiResponse.ok(scriptService.list(userId, state).stream().map(ScriptSummary::of).toList());
    }

    /** 稿件详情（含 hook/body/cta JSON 文本 + citedCardIds；{@code dedupWarnScriptId} 恒为 null——仅生成响应带）。 */
    @GetMapping("/{id}")
    public ApiResponse<ScriptDetail> get(
            @AuthenticationPrincipal Long userId, @PathVariable long id) {
        return ApiResponse.ok(detailOf(userId, id, null));
    }

    /** 单句手改：{section, idx, text}。IDOR：跨用户稿件 → PARAM_INVALID。 */
    @PutMapping("/{id}/sentence")
    public ApiResponse<Void> editSentence(
            @AuthenticationPrincipal Long userId,
            @PathVariable long id,
            @RequestBody EditSentenceRequest req) {
        scriptService.editSentence(userId, id, req.section(), req.idx(), req.text());
        return ApiResponse.ok(null);
    }

    /** 单句 AI 重写预览：{section, idx} → 新句文本。不扣额度、不落库；blocked → CONTENT_BLOCKED。 */
    @PostMapping("/{id}/rewrite-sentence")
    public ApiResponse<RewritePreview> rewriteSentence(
            @AuthenticationPrincipal Long userId,
            @PathVariable long id,
            @RequestBody RewriteSentenceRequest req) {
        String preview = scriptService.rewriteSentence(userId, id, req.section(), req.idx());
        return ApiResponse.ok(new RewritePreview(preview));
    }

    private ScriptDetail detailOf(long userId, long scriptId, Long dedupWarnScriptId) {
        Script s = scriptService.getOwned(userId, scriptId);
        List<Long> cited = cardCitationMapper.findCardIdsByScript(scriptId);
        return ScriptDetail.of(s, cited, dedupWarnScriptId);
    }

    // ---- 请求 / 响应 DTO ----

    public record GenerateRequest(Long topicId, String platform) {}

    public record EditSentenceRequest(String section, int idx, String text) {}

    public record RewriteSentenceRequest(String section, int idx) {}

    public record RewritePreview(String preview) {}

    /**
     * 稿件详情（含三段 JSON 文本 + 引用卡片 id 列表 + 可选查重告警）。{@code dedupWarnScriptId} 仅
     * 生成响应（{@code POST /generate}）非空（命中近复稿），其余端点恒为 null。
     */
    public record ScriptDetail(
            Long id,
            Long topicId,
            String hook,
            String body,
            String cta,
            String platform,
            String reviewState,
            List<Long> citedCardIds,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long dedupWarnScriptId) {
        public static ScriptDetail of(Script s, List<Long> cited, Long dedupWarnScriptId) {
            return new ScriptDetail(
                    s.getId(),
                    s.getTopicId(),
                    s.getHook(),
                    s.getBody(),
                    s.getCta(),
                    s.getPlatform(),
                    s.getReviewState(),
                    cited,
                    s.getCreatedAt(),
                    s.getUpdatedAt(),
                    dedupWarnScriptId);
        }
    }

    /** 稿件列表项（轻量，不含 hook/body/cta）。 */
    public record ScriptSummary(
            Long id,
            Long topicId,
            String platform,
            String reviewState,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        public static ScriptSummary of(Script s) {
            return new ScriptSummary(
                    s.getId(),
                    s.getTopicId(),
                    s.getPlatform(),
                    s.getReviewState(),
                    s.getCreatedAt(),
                    s.getUpdatedAt());
        }
    }
}
