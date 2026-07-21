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
import com.sks.profile.ProfileService;
import com.sks.topic.Topic;
import com.sks.topic.TopicService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li>顺序（「先扣后调，失败必退」；切平台再生成是「不扣不调后扣退」的变体）：
 *       <ol>
 *         <li>同平台免扣检查：同选题同平台已有非 generating/failed 成功稿 → 返回其 id，不扣不调。
 *         <li>切平台再生成判定（design §3 line 121-122「切平台再生成、同选题不加扣」）：同选题已在<b>任意</b>
 *             平台成功过但本平台无成功稿 → 走<b>免费再生成</b>路径（选题已成功过 → 再生成不扣额度；
 *             省约 2/3 token 的前提是再生成本就发生）。否则走首生成（扣费）路径。
 *         <li>插占位行 {@code script(review_state='generating', platform=目标平台)} 拿 {@code scriptId}
 *             （首生成作扣费流水 biz_id 必须在扣费前稳定、退款幂等靠它；再生成虽不扣费但 scriptId 仍需稳定，
 *             供引用溯源与失败行可追溯）——自动提交，无环绕事务。
 *         <li><b>仅首生成</b>调 {@link CreditService#deduct}(uid,1,"generate",scriptId)——独立短事务提交。
 *             余额不足 → 占位行置 failed + 抛 INSUFFICIENT_BALANCE（<b>不退</b>，没扣过）。切平台再生成
 *             <b>不扣</b>——故失败时也<b>不退</b>（没扣过，退了就是多给额度，见 {@link #failWithoutRefund}）。
 *         <li>（事务外）{@link AiClient#scriptGen}——长 HTTP 调用。
 *         <li>成功：回填 hook/body/cta + 置 draft + 写 card_citation，<b>三步包在一个 {@link TransactionTemplate}
 *             短事务</b>（Finding #2：中途引用插入失败则回填一并回滚，script 停在 generating，再由失败路径
 *             generating→failed 干净翻转，无孤儿引用；scriptGen HTTP 调用<b>不在</b>此事务内）。失败（异常 /
 *             blocked / 回填+引用块失败）：占位行 failed + （首生成）{@link CreditService#refund} +
 *             抛 {@link ErrorCode#AI_FAILED}（异常）/ {@link ErrorCode#CONTENT_BLOCKED}（blocked）。
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
    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);

    private final ScriptMapper scriptMapper;
    private final CardCitationMapper cardCitationMapper;
    private final CreditService creditService;
    private final AiClient aiClient;
    private final TopicService topicService;
    private final AppUserMapper appUserMapper;
    private final DedupChecker dedupChecker;
    private final ProfileService profileService;
    private final TransactionTemplate transactionTemplate;

    public ScriptService(
            ScriptMapper scriptMapper,
            CardCitationMapper cardCitationMapper,
            CreditService creditService,
            AiClient aiClient,
            TopicService topicService,
            AppUserMapper appUserMapper,
            DedupChecker dedupChecker,
            ProfileService profileService,
            PlatformTransactionManager transactionManager) {
        this.scriptMapper = scriptMapper;
        this.cardCitationMapper = cardCitationMapper;
        this.creditService = creditService;
        this.aiClient = aiClient;
        this.topicService = topicService;
        this.appUserMapper = appUserMapper;
        this.dedupChecker = dedupChecker;
        this.profileService = profileService;
        // 用于把回填 UPDATE + 引用 INSERT 包成一个短事务（Finding #2）——scriptGen HTTP 调用仍在事务外。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 文案生成编排（§4.1 事务链）。
     *
     * <p><b>不加 {@code @Transactional}</b>——见类注释。返回 {@link GenerateResult}：已成功稿 scriptId +
     * 可选 {@code dedupWarnScriptId}（命中查重则非空，<b>不阻断</b>，PRD §11.2）。
     *
     * <p>三条路径：
     * <ol>
     *   <li><b>同平台免扣短路</b>：同选题同平台已有成功稿 → 返回其 id（dedupWarn=null），不扣不调。
     *   <li><b>切平台再生成（免费）</b>（design §3 line 121-122）：同选题已在任意平台成功过但本平台无成功稿 →
     *       插占位行 → 调 scriptGen（事务外）→ 成功回填+引用（单事务）；失败置 failed 但<b>不退</b>（未扣过）。
     *   <li><b>首生成（扣费）</b>：选题从未成功过 → 插占位行 → 扣费 → 调 scriptGen → 成功回填+引用；
     *       失败置 failed + 退款。
     * </ol>
     *
     * <p><b>查重（PRD §11.2「命中不阻断」）</b>：仅路径 2/3 的首生成成功才查重——回填提交后、事务之外，
     * 调 {@link DedupChecker#findSimilar}（本地 SimHash，纯读 + 本地计算，无 DB 写、无额度介入）。
     * 命中 → 响应带 {@code dedupWarnScriptId}，但稿件仍为 {@code draft}、不退、不阻断。短路路径返回旧稿，
     * 不再查重（旧稿本就存在、无新文本可比）。查重<b>不</b>包在 {@link #backfillAndCite} 事务里——它是
     * 快速本地计算 + 一次 SELECT，单独短读即可，不应与回填事务耦合（若查重异常也不应回滚已提交的回填）。
     *
     * @param userId  当前用户（来自 JWT）
     * @param topicId 选题 id（必须属于本用户）
     * @param platform 平台；null/空 → 取 {@code app_user.default_platform}（§4.2 默认主平台）
     */
    public GenerateResult generate(long userId, long topicId, String platform) {
        // 1. 解析平台：缺省取用户主平台（IDOR：topicService.get 校验选题归属）
        String plat = resolvePlatform(userId, platform);

        // 选题归属校验（跨用户选题 → PARAM_INVALID），同时供 scriptGen 请求拿 title/rationale
        Topic topic = topicService.get(userId, topicId);

        // 2. 同平台免扣短路（§4.2）：同选题同平台已有成功稿 → 返回其 id，不扣不调（dedupWarn=null）
        Long samePlatformId = scriptMapper.findSuccessfulId(userId, topicId, plat);
        if (samePlatformId != null) {
            return new GenerateResult(samePlatformId, null);
        }

        // 3. 切平台再生成判定（design §3 line 121-122）：同选题已在任意平台成功过 → 免费再生成（不扣额度）
        boolean regen = scriptMapper.findSuccessfulIdAnyPlatform(userId, topicId) != null;

        // 4. 插占位行（review_state='generating'）拿稳定 scriptId——首生成作退款幂等键，
        //    再生成虽不扣费但 scriptId 仍供引用溯源与失败行可追溯。自动提交，无环绕事务。
        Script placeholder = new Script();
        placeholder.setUserId(userId);
        placeholder.setTopicId(topicId);
        placeholder.setPlatform(plat);
        scriptMapper.insertPlaceholder(placeholder);
        long scriptId = placeholder.getId();

        // 5. 仅首生成扣费（独立短事务提交）。切平台再生成不扣——失败时也就不退（没扣过，退了是多给额度）。
        //    余额不足 → failed + 抛 INSUFFICIENT_BALANCE（不退，没扣过）。
        if (!regen) {
            try {
                creditService.deduct(userId, 1, "generate", String.valueOf(scriptId));
            } catch (BizException e) {
                scriptMapper.markFailed(scriptId);
                throw e;
            }
        }

        // 6. 事务外调 Python（30-60s）。成功回填+引用；失败置 failed +（首生成）退款 + 抛。
        //    profile 注入 active 定位档案（P2 替换 P1 空桩）：无档案 → 空 Map，script_gen 印「（无定位档案）」。
        Map<String, Object> profile = profileService.activeProfile(userId).orElse(Map.of());
        AiClient.ScriptGenRequest req =
                new AiClient.ScriptGenRequest(
                        userId,
                        new AiClient.TopicRequest(topic.getTitle(), topic.getRationale() == null ? "" : topic.getRationale()),
                        profile,
                        plat);
        AiClient.ScriptGenResult result;
        try {
            result = aiClient.scriptGen(req);
        } catch (RuntimeException e) {
            // 超时 / 连接中断 / 非 2xx（已由 AiClient 翻译为 BizException(AI_FAILED)） / 解析失败
            failScript(userId, scriptId, regen);
            throw e instanceof BizException be ? be : new BizException(ErrorCode.AI_FAILED);
        }

        // blocked → failed +（首生成）退款 + CONTENT_BLOCKED
        if (result.blocked()) {
            failScript(userId, scriptId, regen);
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }

        // 7. 成功 → 回填 hook/body/cta + draft + 写 card_citation，三步包在一个短事务（Finding #2）。
        //    块内抛异常 → 整块回滚（script 停在 generating）→ 走失败路径 generating→failed 干净翻转，无孤儿引用。
        try {
            backfillAndCite(scriptId, result);
        } catch (RuntimeException e) {
            failScript(userId, scriptId, regen);
            throw new BizException(ErrorCode.AI_FAILED);
        }

        // 8. 查重（PRD §11.2「命中不阻断」）——回填已提交后、事务之外执行。本地 SimHash + 一次 SELECT，
        //    纯读、无写、无额度介入。命中 → 仅在响应体带 dedupWarnScriptId，稿件仍 draft、不退、不阻断。
        //    用 in-memory 的 result（hook/body/cta JsonNode）拼纯文本，避免再读一次 DB。异常隔离：查重失败
        //    绝不应让已成功的生成抛错（扣过的不退、已 draft 的不变），故包 try/catch 吞掉只记日志语义（warn=null）。
        Long dedupWarnScriptId = null;
        try {
            String plainText =
                    DedupChecker.flattenPlainText(
                            result.hook() == null ? null : result.hook().toString(),
                            result.body() == null ? null : result.body().toString(),
                            result.cta() == null ? null : result.cta().toString());
            Optional<Long> warn =
                    dedupChecker.findSimilar(
                            userId, scriptId, plainText, dedupChecker.getDefaultThreshold());
            dedupWarnScriptId = warn.orElse(null);
        } catch (RuntimeException e) {
            // 查重失败不应影响已成功的生成——降级为不告警，但留痕以便观测。
            log.warn("dedup check failed for script {}, degrading to no warning", scriptId, e);
            dedupWarnScriptId = null;
        }
        return new GenerateResult(scriptId, dedupWarnScriptId);
    }

    /**
     * 生成结果：{@code scriptId} + 可选 {@code dedupWarnScriptId}（命中查重则非空，<b>不阻断</b>，PRD §11.2）。
     * 前端（Create.tsx）据 {@code dedupWarnScriptId != null} 显示黄条 + 「换角度」按钮（前端后续 task）。
     */
    public record GenerateResult(long scriptId, Long dedupWarnScriptId) {}


    /**
     * 回填（UPDATE script → draft + hook/body/cta JSONB）+ 引用插入循环，包在<b>一个</b> {@link TransactionTemplate}
     * 短事务里（Finding #2）。scriptGen HTTP 调用<b>不在此事务内</b>——本方法仅在 HTTP 成功后落库。
     *
     * <p>若中途引用插入失败，整块回滚：script 停在 generating，引用全无；调用方 {@link #generate}
     * 的 catch 再走失败路径把 generating→failed 干净翻转。避免了「script 已 draft（提交）+ 部分引用
     * （提交）+ failed→退款」的半状态与孤儿引用。适用于首生成与切平台再生成两条成功路径。
     */
    private void backfillAndCite(long scriptId, AiClient.ScriptGenResult result) {
        String hookJson = result.hook() == null ? null : result.hook().toString();
        String bodyJson = result.body() == null ? null : result.body().toString();
        String ctaJson = result.cta() == null ? null : result.cta().toString();
        List<Long> citedCardIds = result.citedCardIds();
        transactionTemplate.executeWithoutResult(
                status -> {
                    scriptMapper.backfill(scriptId, hookJson, bodyJson, ctaJson);
                    if (citedCardIds != null) {
                        for (Long cardId : citedCardIds) {
                            cardCitationMapper.insert(new CardCitation(scriptId, cardId));
                        }
                    }
                });
    }

    /** 失败调度：首生成（{@code regen=false}）→ 置 failed + 退款（幂等，双退安全）；切平台再生成 → 仅置 failed（未扣不退）。 */
    private void failScript(long userId, long scriptId, boolean regen) {
        if (regen) {
            failWithoutRefund(scriptId);
        } else {
            failAndRefund(userId, scriptId);
        }
    }

    /**
     * 退款 + 占位行置 failed——首生成失败路径用（扣过必退）。<b>顺序：先 refund 后 markFailed。</b>
     *
     * <p><b>顺序的承重理由（P1 final review I1）：</b>{@code markFailed}（auto-commit）与 {@code refund}
     * （独立短事务）是两次独立提交。若 {@code markFailed} 先跑、{@code refund} 后跑，二者之间 DB 抖动 /
     * 连接中断会让 {@code markFailed} 已落库（failed）而 {@code refund} 永不执行 → 用户为一条 failed
     * 稿被扣额度、且静默——违反 §4.1 #1「失败必退，永不漏扣」。
     *
     * <p>倒过来：<b>refund 先落</b>（钱立即正确，幂等键 {@code (biz_id, biz_type, 'refund')} 双退安全），
     * 再 {@code markFailed}。若 {@code markFailed} 随后抛，script 停在 {@code generating}（未 failed）——
     * 一个 {@code generating} + 已退款 的行是<b>可对账的中间态</b>（未来 stuck-generating 扫描会标出），
     * 钱已对；而一个 {@code failed} + 未退款 的行是<b>钱丢失且静默</b>。前者严格优于后者。
     *
     * <p><b>不要</b>用 {@link TransactionTemplate} 包二者——refund 独立提交、在随后 markFailed 失败下存活，
     * 正是本顺序要保证的；包进同一事务会让 refund 跟着 markFailed 一起回滚，回到「失败必退」被破坏的状态。
     */
    private void failAndRefund(long userId, long scriptId) {
        creditService.refund(userId, 1, "generate", String.valueOf(scriptId));
        scriptMapper.markFailed(scriptId);
    }

    /**
     * 占位行置 failed，<b>不退款</b>——切平台再生成失败路径用。
     *
     * <p>切平台再生成未扣过额度（选题已成功过 → 再生成免费），故失败时不退——退了就是多给额度。
     * 这是与首生成失败路径（{@link #failAndRefund} 退款）的关键区别：首生成「扣过必退」，
     * 再生成「没扣不退」。
     */
    private void failWithoutRefund(long scriptId) {
        scriptMapper.markFailed(scriptId);
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
     *
     * <p><b>包级可见</b>——仅同包测试用，无 controller 调用（controller 走 IDOR-safe 的 {@link #getOwned}）。
     * 包级可见使「公网入口必走 {@code getOwned}」成为结构约束，而非仅靠约定。
     */
    Script get(long scriptId) {
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
