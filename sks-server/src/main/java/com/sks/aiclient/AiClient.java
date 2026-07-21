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
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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
}
