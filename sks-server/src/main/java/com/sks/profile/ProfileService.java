package com.sks.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.aiclient.AiClient;
import com.sks.aiclient.AiClient.InterviewResultResponse;
import com.sks.aiclient.AiClient.InterviewStepResponse;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.kb.KbCardService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定位校准编排服务（§5 / §4.2 校准免费）——Python 访谈状态机的 Java 编排 + 档案 / A 层卡落库。
 *
 * <p><b>三个端点的编排（{@link ProfileController}）：</b>
 * <ul>
 *   <li><b>访谈推进</b>（{@link #step}）：透传 {@link AiClient#interviewStep}。首轮带 {@code materials}
 *       （用户粘贴的素材文本）+ {@code reply=null}；后续轮带 {@code reply}。一次请求一次 JSON（无流式，
 *       硬不变量）。校准免费，<b>不扣额度</b>。{@code blocked=true}（UGC / LLM 产出命中安全）→
 *       {@link ErrorCode#CONTENT_BLOCKED}，前端提示用户调整后重试。
 *   <li><b>语音回答</b>（{@link #voice}）：{@link AiClient#asr(byte[])} 转文字，<b>仅返回文本</b>——
 *       前端回显给用户确认 / 编辑后再作为该轮 {@code reply} 走 {@link #step}（brief：转出文字先回显再提交）。
 *       ASR 失败 → {@link ErrorCode#AI_FAILED}，前端提示改用文字输入，<b>不阻断访谈</b>。
 *   <li><b>校准生效</b>（{@link #confirm}）：从 checkpoint 只读取 summarize 产出（不再推状态机）→
 *       写 {@code positioning_profile(active=true, version++)} + 批量建 A 层卡；旧 active 行置
 *       {@code active=false} 留历史。
 * </ul>
 *
 * <p><b>confirm 的 HTTP-read-outside-tx 结构（resolution #1）：</b>{@link #confirm} 本身<b>不加</b>
 * {@code @Transactional}——{@link AiClient#interviewResult} 是 Python HTTP 调用（虽是快速 checkpoint 读，
 * 但仍是跨服务调用，不应持 DB 连接）。先在<b>事务外</b>调 interviewResult 取数，再由 {@link #persistConfirm}
 * 通过 {@link TransactionTemplate} 把「旧 active 翻 false + 新 active 插入 + A 卡批量建」三步包成<b>一个短事务</b>
 * 提交（与 {@link com.sks.script.ScriptService#backfillAndCite} 同模式）。若中途任一 A 卡 safetyCheck 拦截，
 * 整事务回滚——旧 active 保持不变（钱不在此路径，无退款编排，原子即可）。
 *
 * <p><b>{@link #activeProfile} 注入</b>：供 P1 {@link com.sks.script.ScriptService#generate} 替换空桩——
 * 返回 active 档案 content（JSONB）的 {@code Map<String,Object>}（或空 Map 表示无档案，script_gen 印
 * 「（无定位档案）」）。{@code Optional.empty()} 仅用于内部判定；ScriptService 用
 * {@code .orElse(Map.of())} 取值。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final AiClient aiClient;
    private final PositioningProfileMapper profileMapper;
    private final KbCardService kbCardService;
    private final TransactionTemplate transactionTemplate;

    public ProfileService(
            AiClient aiClient,
            PositioningProfileMapper profileMapper,
            KbCardService kbCardService,
            PlatformTransactionManager transactionManager) {
        this.aiClient = aiClient;
        this.profileMapper = profileMapper;
        this.kbCardService = kbCardService;
        // 把 deactivate + insert + A 卡批量建包成一个短事务（interviewResult HTTP 读已在事务外完成）。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 推进访谈一轮（透传 Python）。校准免费，不扣额度。
     *
     * <p>{@code blocked=true} → {@link ErrorCode#CONTENT_BLOCKED}（UGC 或 LLM 产出命中安全，状态机未推进）。
     * 非 2xx / 超时由 {@link AiClient#interviewStep} 翻译为 {@link ErrorCode#AI_FAILED}。
     */
    public AiClient.InterviewStepResponse step(
            long userId, String sessionId, String reply, String materials) {
        AiClient.InterviewStepResponse resp = aiClient.interviewStep(userId, sessionId, reply, materials);
        if (resp.blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        return resp;
    }

    /**
     * 语音 → 文字（仅转写，不提交为该轮 reply）。返回文本由前端回显 / 编辑后走 {@link #step}。
     *
     * <p>ASR 失败 → {@link ErrorCode#AI_FAILED}，前端提示改用文字输入，不阻断访谈。
     */
    public String voice(byte[] audioBytes) {
        return aiClient.asr(audioBytes);
    }

    /**
     * 校准生效：从 checkpoint 只读 summarize 产出 → 写 active 档案 + 批量建 A 层卡。
     *
     * <p><b>HTTP 读在事务外</b>（{@link AiClient#interviewResult}），再由 {@link #persistConfirm} 短事务提交写入。
     * {@code threadId = "userId:sessionId"} 与 Python {@code thread_id=f"{user_id}:{session_id}"} 对齐。
     * 无 checkpoint（{@code found=false} 或 profile 为 null）→ {@link ErrorCode#PARAM_INVALID}（访谈未完成）。
     */
    public void confirm(long userId, String sessionId) {
        String threadId = userId + ":" + sessionId;
        InterviewResultResponse result = aiClient.interviewResult(threadId);
        if (!result.found() || result.profile() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "访谈尚未完成，无法确认生效");
        }
        List<AiClient.CardGenCard> aCards = result.aCards() == null ? List.of() : result.aCards();
        persistConfirm(userId, result.profile(), aCards);
    }

    /**
     * 短事务：旧 active 翻 false + 插新 active（version=max+1）+ 批量建 A 层卡（复用
     * {@link KbCardService#create}：A 层无 embedding、safetyCheck belt-and-suspenders）。
     *
     * <p>任一 A 卡 safetyCheck 拦截 → 整事务回滚（旧 active 不变，无钱路径，原子即可）。A 卡 content 为
     * JSON 对象，{@code c.content().toString()} 转 JSON 文本存 JSONB（与 card_gen 同模式）。
     */
    private void persistConfirm(long userId, JsonNode profileJson, List<AiClient.CardGenCard> aCards) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    profileMapper.deactivateActive(userId);
                    int version = profileMapper.maxVersion(userId) + 1;
                    PositioningProfile p = new PositioningProfile();
                    p.setUserId(userId);
                    p.setContent(profileJson.toString());
                    p.setVersion(version);
                    p.setActive(true);
                    profileMapper.insert(p);
                    for (AiClient.CardGenCard c : aCards) {
                        kbCardService.create(userId, "A", c.cardType(), c.title(), c.content().toString());
                    }
                });
    }

    /**
     * 当前用户的 active 档案 content（JSONB）→ {@code Map<String,Object>}。供 P1 script_gen 注入 A 层全量
     * （替换 {@link com.sks.script.ScriptService} 的 {@code Map.of()} 空桩）。
     *
     * <p>无 active 档案返回 {@link Optional#empty()}——调用方（ScriptService）用 {@code .orElse(Map.of())}
     * 取空 Map，script_gen 印「（无定位档案）」。content 解析失败（不应发生）也降级为 empty + WARN。
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> activeProfile(long userId) {
        PositioningProfile p = profileMapper.findActive(userId);
        if (p == null || p.getContent() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of((Map<String, Object>) OM.readValue(p.getContent(), Map.class));
        } catch (Exception e) {
            log.warn("active profile content parse failed for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 把 {@link InterviewStepResponse} 的 {@code stage} 翻译成工作台横幅文案（PRD §11.4「校准进行中，第 X 步」）。
     *
     * <p>不持久化——访谈状态已在 Python checkpoint 里，横幅文案是衍生展示。供 controller 直接返回前端。
     */
    public static String bannerFor(InterviewStepResponse resp) {
        if (resp == null) return "";
        if (resp.done()) return "定位校准已完成，待确认生效";
        if (resp.blocked()) return "定位校准被内容安全拦截";
        String stage = resp.stage() == null ? "" : resp.stage();
        return switch (stage) {
            case "guess_persona" -> "校准进行中，第 1 步：猜人设";
            case "await_feedback" -> "校准进行中，第 2 步：确认人设";
            case "ask" -> "校准进行中：多轮提问";
            case "summarize" -> "校准进行中：归纳档案";
            default -> "校准进行中";
        };
    }
}
