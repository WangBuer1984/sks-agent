package com.sks.review;

import com.sks.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端复盘端点 {@code /api/review/{scriptId}/*}（§4.4）。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。五个端点全 IDOR-scoped（{@code scriptId} 必须属于
 * 认证用户——{@link ReviewService#load} 带 user_id 过滤，跨用户 → PARAM_INVALID，§5.1）。
 *
 * <p><b>复盘 FREE</b>（不扣额度）：所有端点不触 CreditService / credit_ledger。无流式（硬不变量）——
 * 每个端点返回一个 JSON。
 *
 * <ul>
 *   <li>{@code POST /{scriptId}/adopt} —— 采用：draft→pending。
 *   <li>{@code POST /{scriptId}/track} —— 登记发布链接：pending→tracking。
 *   <li>{@code POST /{scriptId}/play} —— 填播放量：tracking→classify→hot/plain/flop。返回判定态。
 *   <li>{@code POST /{scriptId}/attribute} —— 看归因（仅 flop）：调 attributionSingle 返回诊断/建议。FREE。
 *   <li>{@code POST /{scriptId}/feedback} —— rejected 回访反哺：写 source=replay 选题。
 * </ul>
 */
@RestController
@RequestMapping("/api/review/{scriptId}")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 采用稿件：draft→pending。 */
    @PostMapping("/adopt")
    public ApiResponse<Void> adopt(@AuthenticationPrincipal Long userId, @PathVariable long scriptId) {
        reviewService.adopt(userId, scriptId);
        return ApiResponse.ok(null);
    }

    /** 登记发布链接：pending→tracking。body={url}。 */
    @PostMapping("/track")
    public ApiResponse<Void> track(
            @AuthenticationPrincipal Long userId,
            @PathVariable long scriptId,
            @RequestBody TrackRequest req) {
        reviewService.track(userId, scriptId, req.url());
        return ApiResponse.ok(null);
    }

    /** 填播放量：tracking→classify→hot/plain/flop。返回判定态 + playCount。 */
    @PostMapping("/play")
    public ApiResponse<PlayResponse> play(
            @AuthenticationPrincipal Long userId,
            @PathVariable long scriptId,
            @RequestBody PlayRequest req) {
        String state = reviewService.play(userId, scriptId, req.count());
        return ApiResponse.ok(new PlayResponse(state));
    }

    /** 看归因（仅 flop）：返回诊断/建议。FREE。blocked→CONTENT_BLOCKED。 */
    @PostMapping("/attribute")
    public ApiResponse<ReviewService.AttributionView> attribute(
            @AuthenticationPrincipal Long userId, @PathVariable long scriptId) {
        return ApiResponse.ok(reviewService.attribute(userId, scriptId));
    }

    /** rejected 回访反哺：写 source=replay 选题。body={reason}。 */
    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(
            @AuthenticationPrincipal Long userId,
            @PathVariable long scriptId,
            @RequestBody FeedbackRequest req) {
        reviewService.feedback(userId, scriptId, req.reason());
        return ApiResponse.ok(null);
    }

    // ---- 请求 / 响应 DTO ----

    public record TrackRequest(String url) {}

    public record PlayRequest(int count) {}

    public record PlayResponse(String reviewState) {}

    public record FeedbackRequest(String reason) {}
}
