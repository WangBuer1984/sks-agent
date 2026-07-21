package com.sks.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.common.ApiResponse;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 周归因报告 C 端端点 {@code GET /api/review/weekly}（§4.4，Task 4.3）。
 *
 * <p><b>独立 controller</b>（不并入 {@link ReviewController} 的 {@code /api/review/{scriptId}}）——
 * Spring 的 RequestMappingHandlerMapping 优先匹配字面路径 {@code /api/review/weekly}，避免与
 * {@code {scriptId}} 路径变量冲突（否则 {@code weekly} 会被当 scriptId）。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。<b>IDOR-scoped</b>（§5.1）：{@code WHERE user_id = ? AND week_start = ?}，
 * 跨用户查不到 → 返回 null data（不泄露存在性）。
 *
 * <p><b>复盘 FREE</b>（不扣额度）：周归因报告是定时聚合产物，非用户扣费路径。无流式（硬不变量）——
 * 一次返回一个 JSON（content 为 JSONB 解析后的对象，含 summary/wins/gaps/next_focus 或 {blocked:true}）。
 */
@RestController
@RequestMapping("/api/review/weekly")
public class WeeklyReportController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final WeeklyReportMapper weeklyReportMapper;

    public WeeklyReportController(WeeklyReportMapper weeklyReportMapper) {
        this.weeklyReportMapper = weeklyReportMapper;
    }

    /**
     * 取当前用户某周的周归因报告。{@code week=YYYY-MM-DD}（ISO 周一的日期）。
     *
     * <p>无报告（job 未跑 / 用户该周无已复盘稿）→ {@code data=null}（前端据此显示「本周暂无报告」）。
     * {@code week} 非法日期 → PARAM_INVALID。
     */
    @GetMapping
    public ApiResponse<JsonNode> weekly(
            @AuthenticationPrincipal Long userId,
            @RequestParam("week") String week) {
        LocalDate weekStart = parseWeek(week);
        String contentJson = weeklyReportMapper.findContentByUserAndWeek(userId, weekStart);
        if (contentJson == null) {
            return ApiResponse.ok(null);
        }
        try {
            return ApiResponse.ok(OM.readTree(contentJson));
        } catch (Exception e) {
            // content 由 job 写入的合法 JSON——理论上不会到这；兜底返回原始文本
            throw new IllegalStateException("weekly_report content not valid JSON: " + contentJson, e);
        }
    }

    private static LocalDate parseWeek(String week) {
        try {
            return LocalDate.parse(week);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "week 须为 YYYY-MM-DD");
        }
    }
}
