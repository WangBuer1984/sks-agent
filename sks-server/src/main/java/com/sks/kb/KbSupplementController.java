package com.sks.kb;

import com.sks.aiclient.AiClient;
import com.sks.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端补卡端点 {@code /api/kb/supplement}（Task 1.5）。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。两步流程：
 *
 * <ol>
 *   <li>{@code POST /api/kb/supplement {rawText, layer}}：调 card_gen 抽卡 + 缺口 + 冲突检测。
 *       无冲突 → 直接建卡（{@code createdIds} 非空）；有冲突 → 返回 {@code cards/gaps/conflicts}
 *       供前端展示（{@code createdIds=null}，前端调 confirm）。
 *   <li>{@code POST /api/kb/supplement/confirm {layer, cards, conflicts, overwriteCardIds}}：
 *       用户确认后落卡——覆盖选中的冲突卡（旧值归档 {@code card_history}，§11.4）+ 建非冲突卡。
 *       无状态：Java 不重新调 card_gen，cards/conflicts 由前端原样回传，避免 LLM 非确定性。
 * </ol>
 *
 * <p>免费：补卡不扣额度（brief 不列扣费/退款，与 rewrite_sentence 同档）。
 */
@RestController
@RequestMapping("/api/kb/supplement")
public class KbSupplementController {

    private final KbCardService kbCardService;

    public KbSupplementController(KbCardService kbCardService) {
        this.kbCardService = kbCardService;
    }

    /** 补卡第一步：抽卡 + 缺口 + 冲突检测（无冲突直接建卡）。 */
    @PostMapping
    public ApiResponse<KbCardService.SupplementResult> supplement(
            @AuthenticationPrincipal Long userId, @RequestBody SupplementRequest req) {
        return ApiResponse.ok(kbCardService.supplement(userId, req.rawText(), req.layer()));
    }

    /** 补卡第二步：用户确认后落卡（覆盖选中冲突卡 + 建非冲突卡）。 */
    @PostMapping("/confirm")
    public ApiResponse<KbCardService.ConfirmResult> confirmSupplement(
            @AuthenticationPrincipal Long userId, @RequestBody ConfirmSupplementRequest req) {
        return ApiResponse.ok(
                kbCardService.confirmSupplement(
                        userId,
                        req.layer(),
                        req.cards(),
                        req.conflicts(),
                        req.overwriteCardIds()));
    }

    /** supplement 请求体。raw_text 为用户大白话，layer ∈ {A,B,C}。 */
    public record SupplementRequest(String rawText, String layer) {}

    /** confirm 请求体：原样回传 supplement 的 cards + conflicts + 选中的覆盖 id。 */
    public record ConfirmSupplementRequest(
            String layer,
            List<AiClient.CardGenCard> cards,
            List<AiClient.CardGenConflict> conflicts,
            List<Long> overwriteCardIds) {}
}
