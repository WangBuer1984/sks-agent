package com.sks.aiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.time.Duration;
import java.util.List;
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
