package com.sks.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.credit.CreditService;
import com.sks.kb.CardCitation;
import com.sks.kb.CardCitationMapper;
import com.sks.topic.Topic;
import com.sks.topic.TopicService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 稿件创作编排服务——产品核心价值闭环（§4.1 额度事务链）。
 *
 * <p><b>§4.1 事务边界（#1 资金不变量，照此写，勿动）：</b>
 *
 * <ol>
 *   <li><b>{@link #generate} 本身不加 {@code @Transactional}</b>。30-60s 的 {@link AiClient#scriptGen}
 *       HTTP 调用必须在任何事务之外——否则长事务占住连接池连接，且失败时 {@link CreditService#refund}
 *       会在 rollback-only 事务里执行（静默 no-op，漏退额度）。
 *   <li>扣费 / 退款各走 {@link CreditService#deduct} / {@link CreditService#refund}（均
 *       {@code @Transactional(REQUIRED)}）。从非事务的 {@link #generate} 调用 → 各自开独立短事务提交。
 *       <b>不要</b>给 {@link #generate} 加 {@code @Transactional}（会让 deduct/refund JOIN 同一 tx，
 *       失败路径 refund 落 rollback-only tx 不持久化——0.7 err_count 掩盖 bug 同款）。
 *   <li>顺序（「先扣后调，失败必退」）：
 *       <ol>
 *         <li>同选题免扣检查：已有非 generating/failed 成功稿 → 返回其 id，不扣不调。
 *         <li>插占位行 {@code script(review_state='generating')} 拿 {@code scriptId}（扣费流水 biz_id 必须
 *             在扣费前稳定，退款幂等靠它）——自动提交，无环绕事务。
 *         <li>{@link CreditService#deduct}(uid,1,"generate",scriptId)——独立短事务提交。
 *             余额不足 → 占位行置 failed + 抛 INSUFFICIENT_BALANCE（<b>不退</b>，没扣过）。
 *         <li>（事务外）{@link AiClient#scriptGen}——长 HTTP 调用。
 *         <li>成功：回填 hook/body/cta + 置 draft + 写 card_citation。失败（异常 / blocked / 回填失败）：
 *             占位行 failed + {@link CreditService#refund}(uid,1,"generate",scriptId) + 抛
 *             {@link ErrorCode#AI_FAILED}（异常）/ {@link ErrorCode#CONTENT_BLOCKED}（blocked）。
 *             退款靠 {@code (biz_id, biz_type, type)} 唯一约束幂等，双退安全 no-op。
 *       </ol>
 * </ol>
 *
 * <p><b>逐句编辑（§2.1）：</b>{@link #editSentence} 手改单句（解析 JSONB → 替换句 → 整列更新），
 * {@link #rewriteSentence} AI 重写单句（调 Python 返回预览，不落库、不扣额度；用户确认走 editSentence 落库）。
 * 两者均带 IDOR 校验（{@code user_id} 过滤，§5.1）。
 */
@Service
public class ScriptService {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ScriptMapper scriptMapper;
    private final CardCitationMapper cardCitationMapper;
    private final CreditService creditService;
    private final AiClient aiClient;
    private final TopicService topicService;
    private final AppUserMapper appUserMapper;

    public ScriptService(
            ScriptMapper scriptMapper,
            CardCitationMapper cardCitationMapper,
            CreditService creditService,
            AiClient aiClient,
            TopicService topicService,
            AppUserMapper appUserMapper) {
        this.scriptMapper = scriptMapper;
        this.cardCitationMapper = cardCitationMapper;
        this.creditService = creditService;
        this.aiClient = aiClient;
        this.topicService = topicService;
        this.appUserMapper = appUserMapper;
    }

    /**
     * 文案生成编排（§4.1 事务链）。
     *
     * <p><b>不加 {@code @Transactional}</b>——见类注释。返回已成功稿（含本次生成）的 scriptId。
     *
     * @param userId  当前用户（来自 JWT）
     * @param topicId 选题 id（必须属于本用户）
     * @param platform 平台；null/空 → 取 {@code app_user.default_platform}（§4.2 默认主平台）
     */
    public long generate(long userId, long topicId, String platform) {
        // 1. 解析平台：缺省取用户主平台（IDOR：topicService.get 校验选题归属）
        String plat = resolvePlatform(userId, platform);

        // 选题归属校验（跨用户选题 → PARAM_INVALID），同时供 scriptGen 请求拿 title/rationale
        Topic topic = topicService.get(userId, topicId);

        // 2. 同选题免扣（§4.2）：已有非 generating/failed 成功稿 → 直接返回其 id
        Long existingId = scriptMapper.findSuccessfulId(userId, topicId);
        if (existingId != null) {
            return existingId;
        }

        // 3. 插占位行（review_state='generating'）拿稳定 scriptId 作退款幂等键
        Script placeholder = new Script();
        placeholder.setUserId(userId);
        placeholder.setTopicId(topicId);
        placeholder.setPlatform(plat);
        scriptMapper.insertPlaceholder(placeholder);
        long scriptId = placeholder.getId();

        // 4. 扣费（独立短事务提交）。余额不足 → failed + 抛 INSUFFICIENT_BALANCE（不退，没扣过）
        try {
            creditService.deduct(userId, 1, "generate", String.valueOf(scriptId));
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                scriptMapper.markFailed(scriptId);
                throw e;
            }
            // 其它 BizException 不应发生，但稳妥起见同样 failed + 重抛
            scriptMapper.markFailed(scriptId);
            throw e;
        }

        // 5. 事务外调 Python（30-60s）。成功回填；失败 failed + refund + 抛
        AiClient.ScriptGenRequest req =
                new AiClient.ScriptGenRequest(
                        userId,
                        new AiClient.TopicRequest(topic.getTitle(), topic.getRationale() == null ? "" : topic.getRationale()),
                        Map.of(), // profile 桩（ProfileService P2）
                        plat);
        AiClient.ScriptGenResult result;
        try {
            result = aiClient.scriptGen(req);
        } catch (RuntimeException e) {
            // 超时 / 连接中断 / 非 2xx（已由 AiClient 翻译为 BizException(AI_FAILED)） / 解析失败
            failAndRefund(userId, scriptId);
            throw e instanceof BizException be ? be : new BizException(ErrorCode.AI_FAILED);
        }

        // blocked → failed + refund + CONTENT_BLOCKED
        if (result.blocked()) {
            failAndRefund(userId, scriptId);
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }

        // 成功 → 回填 hook/body/cta + draft + 写 card_citation。回填失败 → 视作失败退款
        try {
            String hookJson = result.hook() == null ? null : result.hook().toString();
            String bodyJson = result.body() == null ? null : result.body().toString();
            String ctaJson = result.cta() == null ? null : result.cta().toString();
            scriptMapper.backfill(scriptId, hookJson, bodyJson, ctaJson);
            if (result.citedCardIds() != null) {
                for (Long cardId : result.citedCardIds()) {
                    cardCitationMapper.insert(new CardCitation(scriptId, cardId));
                }
            }
        } catch (RuntimeException e) {
            failAndRefund(userId, scriptId);
            throw new BizException(ErrorCode.AI_FAILED);
        }

        return scriptId;
    }

    /** 占位行置 failed + 退款（幂等，双退安全）。 */
    private void failAndRefund(long userId, long scriptId) {
        scriptMapper.markFailed(scriptId);
        creditService.refund(userId, 1, "generate", String.valueOf(scriptId));
    }

    private String resolvePlatform(long userId, String platform) {
        if (platform != null && !platform.isBlank()) {
            return platform;
        }
        AppUser u = appUserMapper.selectById(userId);
        if (u == null || u.getDefaultPlatform() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户平台未设置");
        }
        return u.getDefaultPlatform();
    }

    // ---- 逐句编辑 ----

    /**
     * 取稿件（含 hook/body/cta）。无 IDOR 校验——供调用方已确权的内部路径与测试
     * （{@code scriptService.get(sid).bodySentence(0)}）。公网入口走 {@link #getOwned}。
     */
    public Script get(long scriptId) {
        Script s = scriptMapper.selectById(scriptId);
        if (s == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件不存在");
        }
        return s;
    }

    /** 取稿件（IDOR：跨用户 → null → PARAM_INVALID，§5.1 不泄露存在性）。供 controller / 编辑路径。 */
    public Script getOwned(long userId, long scriptId) {
        Script s = scriptMapper.findById(scriptId, userId);
        if (s == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "稿件不存在");
        }
        return s;
    }

    /** 当前用户的稿件列表（可选 review_state 过滤）。 */
    public List<Script> list(long userId, String state) {
        return scriptMapper.listByUser(userId, state);
    }

    /**
     * 单句手改（§2.1）：解析 section 的句数组 → 替换 idx 对应句文本 → 整列更新。
     *
     * <p>IDOR：带 user_id 过滤。section ∈ {hook,body,cta}（否则 PARAM_INVALID）。
     */
    public void editSentence(long userId, long scriptId, String section, int idx, String text) {
        if (!isValidSection(section)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "section 必须为 hook/body/cta");
        }
        if (text == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文本不能为空");
        }
        Script s = getOwned(userId, scriptId);
        String json = sectionJson(s, section);
        String updated = replaceSentence(json, idx, text);
        scriptMapper.updateSection(scriptId, userId, section, updated);
    }

    /**
     * 单句 AI 重写（§2.1）：取该句 + 整稿 + 空 profile 调 {@link AiClient#rewriteSentence} → 返回新句预览。
     *
     * <p><b>不扣额度</b>（轻量档；V1.1 限流）。<b>不落库</b>——预览返回前端，用户确认走 {@link #editSentence}。
     * blocked → CONTENT_BLOCKED（原句保留）。IDOR：跨用户稿件 → PARAM_INVALID。
     */
    public String rewriteSentence(long userId, long scriptId, String section, int idx) {
        if (!isValidSection(section)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "section 必须为 hook/body/cta");
        }
        Script s = getOwned(userId, scriptId);
        String sentence = s.sentence(section, idx);
        if (sentence == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该句不存在");
        }
        Map<String, Object> fullScript =
                Map.of(
                        "hook", parseOrEmpty(s.getHook()),
                        "body", parseOrEmpty(s.getBody()),
                        "cta", parseOrEmpty(s.getCta()));
        AiClient.RewriteSentenceRequest req =
                new AiClient.RewriteSentenceRequest(sentence, section, fullScript, Map.of());
        return aiClient.rewriteSentence(req);
    }

    private static boolean isValidSection(String section) {
        return "hook".equals(section) || "body".equals(section) || "cta".equals(section);
    }

    private static String sectionJson(Script s, String section) {
        return switch (section) {
            case "hook" -> s.getHook();
            case "body" -> s.getBody();
            case "cta" -> s.getCta();
            default -> null;
        };
    }

    /** 解析原 JSON 句数组 → 替换 idx 对应句的 text → 序列化回 JSON。无原 JSON 则新建句数组。 */
    private static String replaceSentence(String json, int idx, String text) {
        ObjectNode root;
        ArrayNode sentences;
        try {
            if (json == null || json.isBlank()) {
                root = OM.createObjectNode();
                sentences = root.putArray("sentences");
            } else {
                JsonNode parsed = OM.readTree(json);
                if (parsed.has("sentences") && parsed.get("sentences").isArray()) {
                    root = (ObjectNode) parsed;
                    sentences = (ArrayNode) parsed.get("sentences");
                } else {
                    root = OM.createObjectNode();
                    sentences = root.putArray("sentences");
                }
            }
        } catch (Exception e) {
            root = OM.createObjectNode();
            sentences = root.putArray("sentences");
        }
        boolean replaced = false;
        for (JsonNode s : sentences) {
            if (s.path("idx").asInt(-1) == idx) {
                ((ObjectNode) s).put("text", text);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            ObjectNode ns = sentences.addObject();
            ns.put("idx", idx);
            ns.put("text", text);
        }
        return root.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OM.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
