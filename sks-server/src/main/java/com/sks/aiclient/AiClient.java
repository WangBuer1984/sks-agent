package com.sks.aiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Java → Python 的唯一 HTTP 出口（设计文档 §5.1「Java 是唯一公网入口；Java→Python 每请求带
 * X-Service-Token + X-Request-Id」）。
 *
 * <p><b>基座做一次，后续所有 skill 复用：</b>构造时从配置读 base-url + service-token，
 * 每个 {@link #post} 请求自动带上两个头 + MDC 日志 + 统一超时 + 错误码翻译。业务方法（
 * {@link #embed}/{@link #safetyCheck}）只关心入参出参，不再各自处理鉴权 / 日志 / 超时。
 *
 * <ul>
 *   <li><b>X-Service-Token</b>：共享密钥（来自 .env {@code SERVICE_TOKEN}），Python
 *       {@code verify_service_token} 校验——不匹配返回 403。Token 缺失 / 不匹配是部署 bug，统一翻译为
 *       {@link ErrorCode#AI_FAILED}。每请求都带，业务代码不感知。
 *   <li><b>X-Request-Id</b>：Java 生成的 UUID，Python 仅记录用于串联日志（不校验）。同时写入 MDC
 *       {@code reqId}，让本次调用的所有日志可按 reqId 检索。finally 块清理 MDC，避免线程池复用串味。
 *   <li><b>超时</b>：connect 10s / read 60s。embed / safety 很快，但后续 script_gen（30-60s）/ analyze
 *       可能慢——设一个宽松的读超时，后续 skill 不再各设一遍。
 *   <li><b>重试</b>：仅对 {@link ResourceAccessException}（连接被重置 / 网络抖动）重试一次，简单 YAGNI。
 *   <li><b>错误码翻译</b>：Python 非 2xx（含 403 / 422 / 5xx）→ {@link BizException}(AI_FAILED)，
 *       日志记录 status + body 便于排查。
 * </ul>
 *
 * <p>Python 端返回<b>自己的</b> JSON 形状（如 {@code {"embedding":[...]}} / {@code {"safe":bool}}），
 * <b>不是</b> Java 的 {@code ApiResponse{code,message,data}}——用内部 record 精确对齐 Python pydantic
 * 模型（见 {@link EmbedResponse} / {@link SafetyResponse}）。
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final RestClient restClient;
    private final String serviceToken;

    public AiClient(
            @Value("${sks.ai.base-url:http://sks-ai:8000}") String baseUrl,
            @Value("${sks.service-token:}") String serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        this.restClient =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    // ---- 内部请求 / 响应模型（对齐 Python pydantic）----

    /** Python {@code POST /ai/embed} 请求体 {@code {"text":"..."}}。 */
    private record EmbedRequest(String text) {}

    /** Python {@code POST /ai/embed} 响应体 {@code {"embedding":[...]}}（见 sks-ai embed.py EmbedResponse）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(List<Float> embedding) {}

    /** Python {@code POST /ai/safety/check} 请求体 {@code {"text":"..."}}。 */
    private record SafetyRequest(String text) {}

    /** Python {@code POST /ai/safety/check} 响应体 {@code {"safe":bool}}（见 sks-ai safety.py SafetyResponse）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SafetyResponse(boolean safe) {}

    // ---- 业务方法（后续 skill 增加更多 typed 方法，复用 post 基座）----

    /**
     * 调 Python {@code POST /ai/embed}，返回 1024 维向量（智谱 embedding-3）。
     *
     * <p>B 层卡片新建 / 编辑时同步写 {@code kb_card.embedding} 列（设计 §7.4 立即生效）。
     */
    public float[] embed(String text) {
        EmbedResponse resp = post("/ai/embed", new EmbedRequest(text), EmbedResponse.class);
        List<Float> list = resp.embedding();
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /**
     * 调 Python {@code POST /ai/safety/check}，返回 true=安全。
     *
     * <p>UGC（KB 卡片内容、选题标题等用户直接编辑文本）与 LLM 输出同样要过内容安全（设计 §5.1）。
     * 命中（返回 false）时调用方抛 {@link ErrorCode#CONTENT_BLOCKED}。
     */
    public boolean safetyCheck(String text) {
        SafetyResponse resp = post("/ai/safety/check", new SafetyRequest(text), SafetyResponse.class);
        return resp.safe();
    }

    // ---- script_gen / rewrite_sentence（Task 1.4）----

    /**
     * Python {@code POST /ai/script_gen} 请求体（见 sks-ai/app/api/script_gen.py ScriptGenRequest）。
     *
     * <p>字段名用 {@code @JsonProperty} 对齐 Python 的 snake_case（{@code user_id}），因 RestClient 默认
     * Jackson 不开 SNAKE_CASE——定向标注，不动基座共享 mapper。
     */
    public record ScriptGenRequest(
            @JsonProperty("user_id") long userId,
            @JsonProperty("topic") TopicRequest topic,
            @JsonProperty("profile") Map<String, Object> profile,
            @JsonProperty("platform") String platform) {}

    /** Python {@code TopicRequest {title, rationale}}。 */
    public record TopicRequest(
            @JsonProperty("title") String title,
            @JsonProperty("rationale") String rationale) {}

    /**
     * Python {@code POST /ai/script_gen} 响应（见 ScriptGenResponse）。
     *
     * <p>{@code hook/body/cta} 为 JSON 对象（{@code {sentences:[{idx,text}]}}}），Java 侧用 {@link JsonNode}
     * 承载——Jackson 能把 JSON 对象反序列化成 JsonNode 树，无需定义严格 POJO；service 把
     * {@code node.toString()} 写进 {@code script} 的 JSONB 列（与 {@code KbCard.content} 同模式）。
     *
     * <p><b>blocked 不在此抛异常</b>——返回 {@code blocked=true} 的结果对象，让
     * {@link com.sks.script.ScriptService#generate} 先置 failed + 退款 再抛
     * {@link ErrorCode#CONTENT_BLOCKED}（设计 §4.1：refund 必须在任何异常抛出前完成）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScriptGenResult(
            boolean blocked,
            JsonNode hook,
            JsonNode body,
            JsonNode cta,
            @JsonProperty("cited_card_ids") List<Long> citedCardIds) {}

    /**
     * 调 Python {@code POST /ai/script_gen}，返回生成结果（含 {@code blocked} 标志）。
     *
     * <p>非 2xx（含 token 不匹配的 403 / 422 / 5xx / 超时）由基座 {@link #post} 翻译为
     * {@link ErrorCode#AI_FAILED}。<b>不</b>在此处理 {@code blocked}——交由调用方编排退款。
     */
    public ScriptGenResult scriptGen(ScriptGenRequest req) {
        return post("/ai/script_gen", req, ScriptGenResult.class);
    }

    /**
     * Python {@code POST /ai/rewrite_sentence} 请求体（见 RewriteSentenceRequest）。
     */
    public record RewriteSentenceRequest(
            @JsonProperty("sentence") String sentence,
            @JsonProperty("section") String section,
            @JsonProperty("full_script") Map<String, Object> fullScript,
            @JsonProperty("profile") Map<String, Object> profile) {}

    /** Python {@code RewriteSentenceResponse {text, blocked}}（内部，不暴露给 service）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RewriteResponse(String text, boolean blocked) {}

    /**
     * 调 Python {@code POST /ai/rewrite_sentence}，返回新句文本（预览）。
     *
     * <p>与 {@link #scriptGen} 不同：{@code blocked=true} 时<b>在此抛</b> {@link ErrorCode#CONTENT_BLOCKED}
     * ——单句重写不扣额度、无需 refund 编排，service 直接让异常冒泡即可（设计 §2.1：原句保留）。
     * 成功返回新句文本（service 作为预览返回前端，不落库）。
     */
    public String rewriteSentence(RewriteSentenceRequest req) {
        RewriteResponse resp = post("/ai/rewrite_sentence", req, RewriteResponse.class);
        if (resp.blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        return resp.text();
    }

    // ---- card_gen（Task 1.5 补卡）----

    /**
     * Python {@code POST /ai/card_gen} 请求体（见 sks-ai/app/api/card_gen.py CardGenRequest）。
     *
     * <p>字段名用 {@code @JsonProperty} 对齐 Python 的 snake_case（{@code user_id} / {@code raw_text}
     * / {@code target_layer}）。
     */
    public record CardGenRequest(
            @JsonProperty("user_id") long userId,
            @JsonProperty("raw_text") String rawText,
            @JsonProperty("target_layer") String targetLayer) {}

    /** Python {@code CardGenCard {card_type, title, content}}——content 为 JSON 对象（JsonNode 承载）。 */
    public record CardGenCard(
            @JsonProperty("card_type") String cardType,
            String title,
            JsonNode content) {}

    /**
     * Python {@code CardGenConflict {card_id, card_index, reason}}。
     *
     * <p>{@code card_index} 指向 {@link #cardGen} 返回的 {@code cards} 数组下标，让 Java confirm 流程
     * 能把「新卡」映射到要覆盖的「现有卡 card_id」。
     */
    public record CardGenConflict(
            @JsonProperty("card_id") long cardId,
            @JsonProperty("card_index") int cardIndex,
            String reason) {}

    /**
     * Python {@code POST /ai/card_gen} 响应（见 CardGenResponse）。
     *
     * <p>成功时 {@code {cards, gaps, conflicts}}（blocked 缺省 → Jackson 填 false）；命中安全时
     * {@code {blocked:true}}（cards/gaps/conflicts 缺省 → null）。与 {@link ScriptGenResult} 同模式：
     * <b>不</b>在此处理 {@code blocked}——交由 {@link com.sks.kb.KbCardService#supplement} 翻译为
     * {@link ErrorCode#CONTENT_BLOCKED}（补卡免费，无退款编排，直接抛即可）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardGenResult(
            boolean blocked,
            List<CardGenCard> cards,
            List<String> gaps,
            List<CardGenConflict> conflicts) {}

    /**
     * 调 Python {@code POST /ai/card_gen}，返回抽卡 + 缺口 + 冲突结果（含 {@code blocked} 标志）。
     *
     * <p>非 2xx（含 token 不匹配的 403 / 422 / 5xx / 超时）由基座 {@link #post} 翻译为
     * {@link ErrorCode#AI_FAILED}。<b>不</b>在此处理 {@code blocked}——交由调用方
     * {@link com.sks.kb.KbCardService#supplement} 抛 {@link ErrorCode#CONTENT_BLOCKED}。
     */
    public CardGenResult cardGen(long userId, String rawText, String targetLayer) {
        return post("/ai/card_gen", new CardGenRequest(userId, rawText, targetLayer), CardGenResult.class);
    }

    // ---- interview + asr（Task 2.2 定位校准编排）----

    /**
     * Python {@code POST /ai/interview/step} 请求体（见 sks-ai/app/api/interview.py InterviewStepRequest）。
     *
     * <p>首轮带 {@code materials}（用户粘贴的素材文本）+ {@code user_reply=null}；后续轮带 {@code user_reply}
     * + {@code materials=null}。字段名用 {@code @JsonProperty} 对齐 Python 的 snake_case。
     */
    public record InterviewStepRequest(
            @JsonProperty("user_id") long userId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("user_reply") String userReply,
            String materials) {}

    /**
     * Python {@code POST /ai/interview/step} 响应（见 InterviewStepResponse）。
     *
     * <p>{@code profile_draft} 为 JSON 对象（summarize 完成时返回最终档案），Java 侧用 {@link JsonNode}
     * 承载——与 {@link ScriptGenResult} 的 hook/body/cta 同模式。{@code blocked=true} 时仅此字段有意义
     * （UGC 或 LLM 产出命中安全，状态机不推进）——<b>不在此抛</b>，交由 {@link
     * com.sks.profile.ProfileService#step} 翻译为 {@link ErrorCode#CONTENT_BLOCKED}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterviewStepResponse(
            String stage,
            String question,
            @JsonProperty("profile_draft") JsonNode profileDraft,
            boolean done,
            boolean blocked) {}

    /**
     * Python {@code GET /ai/interview/result} 响应（见 InterviewResultResponse）。
     *
     * <p>只读：从 checkpoint 取 summarize 产出，不推进状态机。{@code profile} 为最终档案（JSON 对象，
     * {@code JsonNode} 承载）；{@code a_cards} 为 A 层卡草稿列表，形状与 {@link CardGenCard} 一致
     * （{@code {card_type, title, content}}，{@code content} 为 JSON 对象）——故复用同一 record。
     * {@code found=false} 表示无 checkpoint（访谈未完成）——由 {@link
     * com.sks.profile.ProfileService#confirm} 翻译为 PARAM_INVALID。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterviewResultResponse(
            JsonNode profile,
            @JsonProperty("a_cards") List<CardGenCard> aCards,
            boolean found) {}

    /**
     * 调 Python {@code POST /ai/interview/step}，推进访谈状态机一轮。
     *
     * <p>校准是免费的（PRD §4.2），无额度逻辑——{@link com.sks.profile.ProfileService} 不扣不退。
     * 非 2xx（含 token 不匹配的 403 / 422 / 5xx / 超时）由基座 {@link #post} 翻译为
     * {@link ErrorCode#AI_FAILED}。<b>不</b>在此处理 {@code blocked}——交由调用方翻译。
     */
    public InterviewStepResponse interviewStep(
            long userId, String sessionId, String userReply, String materials) {
        return post(
                "/ai/interview/step",
                new InterviewStepRequest(userId, sessionId, userReply, materials),
                InterviewStepResponse.class);
    }

    /**
     * 调 Python {@code GET /ai/interview/result?thread_id=}，只读取 summarize 产出。
     *
     * <p>{@code threadId} 由 Java 构造为 {@code "userId:sessionId"}（与 Python
     * {@code thread_id=f"{user_id}:{session_id}"} 对齐，见 interview.graph.interview_step）。
     * GET 请求复用与 {@link #post} 相同的 {@code X-Service-Token} + {@code X-Request-Id} 头 +
     * MDC + 重试 + 错误码翻译（见 {@link #get}）。
     */
    public InterviewResultResponse interviewResult(String threadId) {
        return get("/ai/interview/result?thread_id={tid}", InterviewResultResponse.class, threadId);
    }

    /** Python {@code POST /ai/asr} 响应体 {@code {"text":"..."}}（见 sks-ai/app/api/asr.py ASRResponse）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AsrResponse(String text) {}

    /**
     * 调 Python {@code POST /ai/asr}（multipart 音频），返回转写文本。
     *
     * <p>音频由 {@link com.sks.profile.ProfileController} 的 {@code /api/profile/voice} 转发——用户不直接
     * 调 Python。返回的文本由前端回显给用户确认 / 编辑后再作为该轮 {@code reply} 走 {@link #interviewStep}。
     * ASR 失败（Python 502/503）或非 2xx / 超时 → {@link BizException}(AI_FAILED)，由调用方提示改用文字输入，
     * <b>不阻断访谈</b>。multipart 用 {@link ByteArrayResource}（覆盖 {@code getFilename} 让
     * {@code FormHttpMessageConverter} 带 filename）。
     */
    public String asr(byte[] audioBytes) {
        String reqId = UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
        try {
            try {
                return executeAsr(audioBytes, reqId);
            } catch (ResourceAccessException e) {
                log.warn("AI connection error (retrying): {}", e.getMessage());
                return executeAsr(audioBytes, reqId);
            }
        } catch (RestClientResponseException e) {
            log.warn(
                    "AI service error: path=/ai/asr, status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new BizException(ErrorCode.AI_FAILED);
        } catch (ResourceAccessException e) {
            // 第二次（重试后）仍不可达 / 超时 → 翻译为 AI_FAILED（与 post 同档）。
            log.warn("AI service unreachable/timeout (after retry): path=/ai/asr, msg={}", e.getMessage());
            throw new BizException(ErrorCode.AI_FAILED, "AI 服务不可达/超时");
        } finally {
            MDC.remove("reqId");
        }
    }

    private String executeAsr(byte[] audioBytes, String reqId) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        // 覆盖 getFilename 让 multipart 带文件名（FormHttpMessageConverter 需要）。
        parts.add(
                "audio",
                new ByteArrayResource(audioBytes) {
                    @Override
                    public String getFilename() {
                        return "audio.wav";
                    }
                });
        return restClient
                .post()
                .uri("/ai/asr")
                .header("X-Service-Token", serviceToken)
                .header("X-Request-Id", reqId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(AsrResponse.class)
                .text();
    }

    // ---- hotBoard（Task 1.7 热点榜；P3 Task 3.3 Step 3.5 真实接线）----

    /**
     * 热点榜条目（Python {@code GET /ai/hot_board} 返回数组的一项，对齐 HotItemResponse）。
     *
     * <p>字段名用 {@code @JsonProperty} 对齐 Python snake_case（{@code hot_index} / {@code video_count}）。
     * <b>非 UGC</b>：热点标题来自平台热榜（TikHub），不经 safetyCheck——与用户自建 faq 选题（UGC）
     * 区别对待（brief 明确不双重过审）。{@code hotIndex} / {@code videoCount} 为热度/视频数 hint，
     * Java 侧目前仅用 title 打分入库（{@link com.sks.topic.TopicService#scoreHotTopicsForUser}），
     * 两个字段保留供后续 V1.1 排序 / 配额使用。
     */
    public record HotItem(
            String title,
            @JsonProperty("hot_index") Integer hotIndex,
            @JsonProperty("video_count") Integer videoCount) {}

    /**
     * 调 Python {@code GET /ai/hot_board} 取当前平台热点榜。
     *
     * <p>P3 Task 3.3 Step 3.5 真实接线：替换 P1 空桩为 {@link #get} 实调。Python 封装 TikHub 热榜
     * （Task 3.2 已产出）。非 2xx（含 token 不匹配 / TikHub 不可达 502）由基座 {@link #get} 翻译为
     * {@link BizException}(AI_FAILED)；调用方 {@link com.sks.topic.HotTopicJob} per-user try/catch
     * 兜底，单次失败不影响其他用户。
     */
    public List<HotItem> hotBoard() {
        HotItem[] arr = get("/ai/hot_board", HotItem[].class);
        return arr == null ? List.of() : java.util.Arrays.asList(arr);
    }

    // ---- analyze（Task 3.3 拆视频 / 拆账号编排）----

    /**
     * Python {@code POST /ai/analyze/precheck {url}} 响应（对齐 PrecheckResponse）。
     *
     * <p>{@code video_count} 为 TikHub 首页<b>估算</b>（≤20，Task 3.1 Q2 fix），非精确总数——
     * 拆账号扣费公式 {@code max(1,min(10,floor(N/2)))} 用此估算，是 §4.3 接受的契约。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Precheck(boolean reachable, @JsonProperty("video_count") int videoCount) {}

    /** Python {@code POST /ai/analyze/precheck} 请求体 {@code {"url":"..."}}。 */
    private record PrecheckRequest(String url) {}

    /**
     * 调 Python {@code POST /ai/analyze/precheck}（免费，不扣费）。账号拆解预检：校验 URL 可达 +
     * 取首页视频数估算。不可达 / N=0 由 {@link com.sks.analyze.AnalyzeService#startAccount} 拒绝（不扣费）。
     */
    public Precheck precheck(String url) {
        return post("/ai/analyze/precheck", new PrecheckRequest(url), Precheck.class);
    }

    /**
     * Python {@code POST /ai/analyze/video/text} 响应（对齐 structure_video 返回 dict）。
     *
     * <p>成功返回 {@code {structure, why_hot, framework, diff_hint}}；命中安全返回 {@code {blocked:true}}
     * （Python 不写 result，Java 决策退/不退）。字段名用 {@code @JsonProperty} 对齐 Python snake_case。
     * 与 {@link ScriptGenResult} 同模式：<b>不在此处理 blocked</b>——交由 {@link
     * com.sks.analyze.AnalyzeService#startVideoText} 编排退款 + 抛 {@link ErrorCode#CONTENT_BLOCKED}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VideoTextResult(
            boolean blocked,
            String structure,
            @JsonProperty("why_hot") String whyHot,
            String framework,
            @JsonProperty("diff_hint") String diffHint) {}

    /** Python {@code POST /ai/analyze/video/text} 请求体 {@code {task_id, transcript}}（对齐 VideoTextRequest）。 */
    public record VideoTextRequest(
            @JsonProperty("task_id") long taskId, String transcript) {}

    /**
     * 调 Python {@code POST /ai/analyze/video/text}（同步结构化）。Python 内部会写
     * {@code analyze_task(status='done', result)}，但 Java 仍按 §4.1 占位模式 backfill
     * （幂等重写，防 Python 写后 Java 读 HTTP 失败的中间态）。
     */
    public VideoTextResult analyzeVideoText(long taskId, String transcript) {
        return post("/ai/analyze/video/text", new VideoTextRequest(taskId, transcript), VideoTextResult.class);
    }

    /** Python {@code POST /ai/analyze/video/link} / {@code POST /ai/analyze/account} 的 202 响应体 {@code {task_id}}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzeAccepted(@JsonProperty("task_id") long taskId) {}

    /** Python {@code POST /ai/analyze/video/link} 请求体 {@code {task_id, url}}（对齐 VideoLinkRequest）。 */
    public record VideoLinkRequest(@JsonProperty("task_id") long taskId, String url) {}

    /**
     * 调 Python {@code POST /ai/analyze/video/link}（异步 202）。Python 端点先写 running+updated_at，
     * 再 BackgroundTasks 跑 transcribe→结构化→done/failed（进度直写 analyze_task）。
     * 返回 202 body {@code {task_id}}——Java 仅确认受理，实际结果由 {@link com.sks.analyze.AnalyzeTaskPoller} 轮询。
     */
    public AnalyzeAccepted analyzeVideoLink(long taskId, String url) {
        return post("/ai/analyze/video/link", new VideoLinkRequest(taskId, url), AnalyzeAccepted.class);
    }

    /** Python {@code POST /ai/analyze/account} 请求体 {@code {task_id, url}}（对齐 AccountRequest）。 */
    public record AccountRequest(@JsonProperty("task_id") long taskId, String url) {}

    /**
     * 调 Python {@code POST /ai/analyze/account}（异步 202）。Python BackgroundTasks 跑 TOP20→逐条→
     * 三层→done/partial/failed，进度 {@code floor(done*100/total)} 直写 analyze_task。
     * 返回 202 body {@code {task_id}}。
     */
    public AnalyzeAccepted analyzeAccount(long taskId, String url) {
        return post("/ai/analyze/account", new AccountRequest(taskId, url), AnalyzeAccepted.class);
    }

    // ---- 基座：headers + timeout + retry + error translation + MDC ----

    /**
     * 通用 POST：所有 typed 方法走这里。
     *
     * <p>设置 {@code X-Service-Token} + {@code X-Request-Id} 头 + MDC，一次重试
     * （{@link ResourceAccessException}），非 2xx 翻译为 {@link BizException}(AI_FAILED)。
     */
    private <T> T post(String path, Object body, Class<T> respType) {
        String reqId = UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
        try {
            return postWithRetry(path, body, respType, reqId);
        } catch (RestClientResponseException e) {
            log.warn(
                    "AI service error: path={}, status={}, body={}",
                    path,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new BizException(ErrorCode.AI_FAILED);
        } catch (ResourceAccessException e) {
            // 第二次（重试后）仍连接不可达 / 超时：postWithRetry 的首次 ResourceAccessException
            // 已被吞掉重试，但二次抛出会逃出 try ——此处兜住，翻译为 AI_FAILED（与 5xx 同档），
            // 避免裸 ResourceAccessException 冒成通用 500。
            log.warn("AI service unreachable/timeout (after retry): path={}, msg={}", path, e.getMessage());
            throw new BizException(ErrorCode.AI_FAILED, "AI 服务不可达/超时");
        } finally {
            MDC.remove("reqId");
        }
    }

    private <T> T postWithRetry(String path, Object body, Class<T> respType, String reqId) {
        try {
            return execute(path, body, respType, reqId);
        } catch (ResourceAccessException e) {
            log.warn("AI connection error (retrying): {}", e.getMessage());
            return execute(path, body, respType, reqId);
        }
    }

    private <T> T execute(String path, Object body, Class<T> respType, String reqId) {
        return restClient
                .post()
                .uri(path)
                .header("X-Service-Token", serviceToken)
                .header("X-Request-Id", reqId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(respType);
    }

    /**
     * 通用 GET：镜像 {@link #post} 的头 + MDC + 重试 + 错误码翻译，但不带 body（GET 无 body）。
     *
     * <p>{@code path} 含 URI 变量（如 {@code ?thread_id={tid}}），{@code uriVars} 按 {@link
     * RestClient.RequestHeadersUriSpec#uri(String, Object...)} 顺序填入并自动 URL-encode。
     * 供 {@link #interviewResult} 等 GET typed 方法复用。
     */
    private <T> T get(String path, Class<T> respType, Object... uriVars) {
        String reqId = UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
        try {
            try {
                return executeGet(path, respType, reqId, uriVars);
            } catch (ResourceAccessException e) {
                log.warn("AI connection error (retrying): {}", e.getMessage());
                return executeGet(path, respType, reqId, uriVars);
            }
        } catch (RestClientResponseException e) {
            log.warn(
                    "AI service error: path={}, status={}, body={}",
                    path,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new BizException(ErrorCode.AI_FAILED);
        } catch (ResourceAccessException e) {
            log.warn("AI service unreachable/timeout (after retry): path={}, msg={}", path, e.getMessage());
            throw new BizException(ErrorCode.AI_FAILED, "AI 服务不可达/超时");
        } finally {
            MDC.remove("reqId");
        }
    }

    private <T> T executeGet(String path, Class<T> respType, String reqId, Object... uriVars) {
        return restClient
                .get()
                .uri(path, uriVars)
                .header("X-Service-Token", serviceToken)
                .header("X-Request-Id", reqId)
                .retrieve()
                .body(respType);
    }
}
