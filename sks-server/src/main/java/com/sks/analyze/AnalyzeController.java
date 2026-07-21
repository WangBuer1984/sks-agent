package com.sks.analyze;

import com.sks.aiclient.AiClient;
import com.sks.common.ApiResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端拆解端点 {@code /api/analyze}（§4.3）。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。四个端点：
 *
 * <ul>
 *   <li>{@code POST /video/text} —— 同步：粘文案 → 结构化拆解（扣 1，一次 JSON 返回，无流式）。
 *   <li>{@code POST /video/link} —— 异步：粘链接 → 返回 taskId，前端轮询。
 *   <li>{@code POST /account} —— 异步：粘账号链接 → precheck → 扣 {@code max(1,min(10,floor(N/2)))} →
 *       返回 taskId，前端轮询。
 *   <li>{@code GET /tasks/{id}} —— 任务详情（含 status/progress/result + TOP20 明细，IDOR 校验）。
 * </ul>
 *
 * <p><b>无流式</b>（硬不变量）：202 返回 taskId，前端轮询 {@code GET /tasks/{id}}，进度直写 analyze_task。
 */
@RestController
@RequestMapping("/api/analyze")
public class AnalyzeController {

    private final AnalyzeService analyzeService;

    public AnalyzeController(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    /** 拆视频（粘文案）——同步结构化拆解。扣 1。返回四字段结果（无流式）。 */
    @PostMapping("/video/text")
    public ApiResponse<VideoTextResponse> videoText(
            @AuthenticationPrincipal Long userId, @RequestBody VideoTextRequest req) {
        AiClient.VideoTextResult r = analyzeService.startVideoText(userId, req.transcript());
        return ApiResponse.ok(
                new VideoTextResponse(r.structure(), r.whyHot(), r.framework(), r.diffHint()));
    }

    /** 拆视频（粘链接）——异步。返回 taskId，前端轮询 {@code GET /tasks/{id}}。 */
    @PostMapping("/video/link")
    public ApiResponse<TaskAccepted> videoLink(
            @AuthenticationPrincipal Long userId, @RequestBody VideoLinkRequest req) {
        long taskId = analyzeService.startVideoLink(userId, req.url());
        return ApiResponse.ok(new TaskAccepted(taskId));
    }

    /** 拆账号——异步。precheck → 扣 {@code max(1,min(10,floor(N/2)))} → 返回 taskId。 */
    @PostMapping("/account")
    public ApiResponse<TaskAccepted> account(
            @AuthenticationPrincipal Long userId, @RequestBody AccountRequest req) {
        long taskId = analyzeService.startAccount(userId, req.url());
        return ApiResponse.ok(new TaskAccepted(taskId));
    }

    /** 任务详情（status/progress/result + TOP20 明细）。IDOR：跨用户 → PARAM_INVALID。 */
    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskDetail> getTask(
            @AuthenticationPrincipal Long userId, @PathVariable long id) {
        AnalyzeTask t = analyzeService.getTask(userId, id);
        List<BenchmarkVideo> videos = List.of();
        // partial 也展示 TOP20：Python 已为成功条目写 benchmark_video 行（brief 无 done-only 限定）。
        if ("account".equals(t.getTaskType())
                && ("done".equals(t.getStatus()) || "partial".equals(t.getStatus()))) {
            videos = analyzeService.listBenchmarkVideos(id);
        }
        return ApiResponse.ok(TaskDetail.of(t, videos));
    }

    // ---- 请求 / 响应 DTO ----

    public record VideoTextRequest(String transcript) {}

    public record VideoLinkRequest(String url) {}

    public record AccountRequest(String url) {}

    public record TaskAccepted(long taskId) {}

    /** video/text 同步结果（对齐 Python {structure, why_hot, framework, diff_hint}）。 */
    public record VideoTextResponse(
            String structure, String whyHot, String framework, String diffHint) {}

    /** 任务详情（含 TOP20 明细 + 三层 result JSON 文本）。 */
    public record TaskDetail(
            Long id,
            String taskType,
            String status,
            Integer progress,
            Integer charged,
            String result,
            String error,
            OffsetDateTime updatedAt,
            OffsetDateTime createdAt,
            List<BenchmarkVideoView> videos) {
        public static TaskDetail of(AnalyzeTask t, List<BenchmarkVideo> videos) {
            return new TaskDetail(
                    t.getId(),
                    t.getTaskType(),
                    t.getStatus(),
                    t.getProgress(),
                    t.getCharged(),
                    t.getResult(),
                    t.getError(),
                    t.getUpdatedAt(),
                    t.getCreatedAt(),
                    videos.stream().map(BenchmarkVideoView::of).toList());
        }
    }

    /** TOP20 明细视图（含 structure JSON 文本，供前端展开）。 */
    public record BenchmarkVideoView(
            Long id,
            String title,
            Long playCount,
            Long favCount,
            String transcript,
            String structure,
            OffsetDateTime createdAt) {
        public static BenchmarkVideoView of(BenchmarkVideo v) {
            return new BenchmarkVideoView(
                    v.getId(),
                    v.getTitle(),
                    v.getPlayCount(),
                    v.getFavCount(),
                    v.getTranscript(),
                    v.getStructure(),
                    v.getCreatedAt());
        }
    }
}
