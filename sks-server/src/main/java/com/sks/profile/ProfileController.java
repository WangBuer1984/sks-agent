package com.sks.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.sks.aiclient.AiClient.InterviewStepResponse;
import com.sks.common.ApiResponse;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * C 端定位校准端点 {@code /api/profile/**}（§5 / §4.2 校准免费）。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。三个端点：
 *
 * <ul>
 *   <li>{@code POST /api/profile/interview}：透传 Python 访谈一步；首轮带 {@code materials}，后续轮带 {@code reply}。
 *   <li>{@code POST /api/profile/voice}（multipart）：转发 Python ASR，返回转写文本（前端回显 / 编辑后走 /interview）。
 *   <li>{@code POST /api/profile/confirm}：从 checkpoint 取 summarize 产出 → 写 active 档案 + 批量建 A 层卡。
 * </ul>
 *
 * <p><b>Java 是唯一公网入口</b>（设计 §5.1）：用户不直接调 Python {@code /ai/*}，语音经本端点转发，
 * 带 {@code X-Service-Token} 由 {@link com.sks.aiclient.AiClient} 自动注入。校准免费，<b>无额度逻辑</b>。
 * 返回 {@link ApiResponse}（code=0 成功）；BizException 由 {@link com.sks.common.GlobalExceptionHandler} 翻译。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 推进访谈一轮。首轮 {@code materials} 必填（用户粘贴的素材文本）、{@code reply=null}；后续轮
     * {@code reply} 必填、{@code materials=null}。返回当前 {@code stage/question/profile_draft/done/blocked}。
     */
    @PostMapping("/interview")
    public ApiResponse<InterviewStepView> interview(
            @AuthenticationPrincipal Long userId, @RequestBody InterviewRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        InterviewStepResponse resp =
                profileService.step(userId, req.sessionId(), req.reply(), req.materials());
        return ApiResponse.ok(
                new InterviewStepView(
                        resp.stage(),
                        resp.question(),
                        resp.profileDraft(),
                        resp.done(),
                        resp.blocked(),
                        ProfileService.bannerFor(resp)));
    }

    /**
     * 语音回答：multipart 音频 → ASR 文本。仅返回文本（前端回显 / 编辑后走 /interview）。
     *
     * <p>ASR 失败 → AI_FAILED（前端提示改用文字输入，不阻断访谈）。
     */
    @PostMapping("/voice")
    public ApiResponse<String> voice(
            @AuthenticationPrincipal Long userId, @RequestParam("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "音频为空");
        }
        try {
            return ApiResponse.ok(profileService.voice(audio.getBytes()));
        } catch (Exception e) {
            // getBytes 抛 java.io.IOException（理论罕见）；统一翻译为 AI_FAILED，不裸冒 500。
            throw new BizException(ErrorCode.AI_FAILED, "音频读取失败");
        }
    }

    /**
     * 校准生效：写 active 档案 + 批量建 A 层卡。访谈未完成（无 checkpoint / profile）→ PARAM_INVALID。
     */
    @PostMapping("/confirm")
    public ApiResponse<Void> confirm(
            @AuthenticationPrincipal Long userId, @RequestBody ConfirmRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        profileService.confirm(userId, req.sessionId());
        return ApiResponse.ok(null);
    }

    /** 访谈推进请求体。首轮 materials 非空、reply=null；后续轮 reply 非空、materials=null。 */
    public record InterviewRequest(String sessionId, String reply, String materials) {}

    /** confirm 请求体。 */
    public record ConfirmRequest(String sessionId) {}

    /**
     * /interview 响应体（对齐 Python {@code InterviewStepResponse} + 工作台横幅文案 {@code banner}）。
     *
     * <p>{@code profileDraft} 为 JSON 对象（summarize 完成时返回最终档案），Jackson 以 {@link JsonNode}
     * 序列化为 JSON 对象回前端——前端按 {@code done=true} 展示档案草稿 + 确认按钮。
     */
    public record InterviewStepView(
            String stage,
            String question,
            JsonNode profileDraft,
            boolean done,
            boolean blocked,
            String banner) {}
}
