package com.sks.kb;

import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库卡片 CRUD 服务。
 *
 * <p>三层结构（A=人物画像 / B=产品知识 / C=金句素材）对应不同的 embedding 策略与归档策略：
 *
 * <ul>
 *   <li><b>UGC 内容安全（设计 §5.1）</b>：{@code create} / {@code update} 先对 {@code title + " " + content}
 *       调 {@link AiClient#safetyCheck}，不安全抛 {@link ErrorCode#CONTENT_BLOCKED} 且<b>不落库</b>——
 *       用户直接编辑的卡片内容与 card_gen LLM 输出同标准过审。
 *   <li><b>B 层 embedding 立即同步（§7.4）</b>：B 层 create / update 同步调 {@link AiClient#embed} 写
 *       {@code embedding} 列（非 lazy）；A/C 层 embedding 恒 null。embed 输入文本取 {@code title + " " + content}
 *       （两者都是有语义的文本，拼接让向量覆盖更全）。
 *   <li><b>B 层编辑归档</b>：编辑 B 层卡片时把<b>旧</b> content 写 {@code card_history}，再写新值 +
 *       重算 embedding。A/C 层编辑不归档（brief 字面：仅 B 层内容编辑触发归档）。
 *   <li><b>删除保护</b>：删前查 {@code card_citation} 引用数，{@code >0 且 !force} 抛
 *       {@link ErrorCode#CARD_IN_USE}（消息携带计数，前端展示「有 N 篇稿件引用此卡」+ 二次确认）；
 *       {@code force=true} 软删（手动 {@code SET deleted=true}，绕开 MyBatis-Plus int/boolean 不匹配）。
 * </ul>
 *
 * <p>{@code @Transactional} 保证归档 + 重算 + 更新原子（B 层编辑路径）；create 的 safetyCheck→embed→insert
 * 也在事务内（AiClient 是 HTTP 调用，但 KB 路径的 embed 很快，不像 script_gen 有 30-60s 长调用——
 * 持有连接的时间可接受；后续 script_gen / analyze 路径才需 {@code REQUIRES_NEW} 把长调用排除在事务外）。
 */
@Service
public class KbCardService {

    private final KbCardMapper kbCardMapper;
    private final CardHistoryMapper cardHistoryMapper;
    private final CardCitationMapper cardCitationMapper;
    private final AiClient aiClient;

    public KbCardService(
            KbCardMapper kbCardMapper,
            CardHistoryMapper cardHistoryMapper,
            CardCitationMapper cardCitationMapper,
            AiClient aiClient) {
        this.kbCardMapper = kbCardMapper;
        this.cardHistoryMapper = cardHistoryMapper;
        this.cardCitationMapper = cardCitationMapper;
        this.aiClient = aiClient;
    }

    /**
     * 新建卡片。
     *
     * <ol>
     *   <li>UGC 过审：title + " " + content → safetyCheck，不安全抛 CONTENT_BLOCKED，不落库。
     *   <li>B 层：embed(title + " " + content) 算向量；A/C 层：embedding = null。
     *   <li>insert kb_card，返回生成的 id。
     * </ol>
     */
    @Transactional
    public long create(long userId, String layer, String cardType, String title, String content) {
        if (!aiClient.safetyCheck(title + " " + content)) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        KbCard card = new KbCard();
        card.setUserId(userId);
        card.setLayer(layer);
        card.setCardType(cardType);
        card.setTitle(title);
        card.setContent(content);
        if ("B".equals(layer)) {
            card.setEmbedding(aiClient.embed(title + " " + content));
        }
        kbCardMapper.insertCard(card);
        return card.getId();
    }

    /**
     * 编辑卡片（改 title + content）。
     *
     * <p>顺序（safety-check-then-archive）：先 {@link AiClient#safetyCheck} 新值，通过后才归档旧 content
     * 到 card_history + 重算 embedding + 落库。这样安全拦截时事务回滚，<b>不会</b>留下指向被拒新值的
     * 脏历史，旧值也保持不变。B 层：归档 + embed + updateWithEmbedding；A/C 层：updateNoEmbedding
     * （不归档、不算向量）。
     *
     * <p><b>IDOR 防护</b>（设计 §5.1）：{@code userId} 必须与卡片 {@code user_id} 匹配，
     * 否则 {@link KbCardMapper#findById} 返回 null → 抛 {@link ErrorCode#PARAM_INVALID}，
     * 不泄露「存在但不属于你」。
     */
    @Transactional
    public void update(long userId, long id, String title, String content) {
        KbCard old = kbCardMapper.findById(id, userId);
        if (old == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "卡片不存在或已删除");
        }
        if (!aiClient.safetyCheck(title + " " + content)) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        if ("B".equals(old.getLayer())) {
            cardHistoryMapper.insertHistory(id, old.getContent());
            float[] emb = aiClient.embed(title + " " + content);
            kbCardMapper.updateWithEmbedding(id, title, content, emb);
        } else {
            kbCardMapper.updateNoEmbedding(id, title, content);
        }
    }

    /**
     * 删除卡片（软删）。
     *
     * <p>查引用数：{@code >0 且 !force} 抛 CARD_IN_USE（消息带计数）；否则（force 或无引用）软删
     * {@code SET deleted=true}。
     *
     * <p><b>IDOR 防护</b>（设计 §5.1）：{@code userId} 必须与卡片 {@code user_id} 匹配，
     * {@link KbCardMapper#softDelete} 的 WHERE 带 user_id，跨用户删除影响 0 行 → 抛
     * {@link ErrorCode#PARAM_INVALID}。
     */
    @Transactional
    public void delete(long userId, long id, boolean force) {
        KbCard card = kbCardMapper.findById(id, userId);
        if (card == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "卡片不存在或已删除");
        }
        int citations = cardCitationMapper.countByCard(id);
        if (citations > 0 && !force) {
            throw new BizException(
                    ErrorCode.CARD_IN_USE, "有 " + citations + " 篇稿件引用此卡，删除将影响它们，请确认是否强制删除");
        }
        int rows = kbCardMapper.softDelete(id, userId);
        if (rows == 0) {
            // 二次防御：findById 通过后 softDelete 仍 0 行（并发删除等）→ 当作不存在
            throw new BizException(ErrorCode.PARAM_INVALID, "卡片不存在或已删除");
        }
    }

    /** 列出当前用户的未删卡片（可选 layer 过滤 A/B/C），不含 embedding。 */
    public List<CardSummary> list(long userId, String layer) {
        return kbCardMapper.listByUser(userId, layer);
    }

    /**
     * 在当前用户 B 层卡片上做 pgvector 余弦匹配（热点打分用，Task 1.7）——委托
     * {@link KbCardMapper#findBestBMatches}，阈值 {@code 0.25}（= 相似度 {@code >= 0.75}，与
     * {@code rag/retrieve.py} 默认一致），取 top-1 最佳命中。无命中返回 {@link java.util.Optional#empty}。
     *
     * <p>放在 KbCardService 而非 TopicService 直查 mapper，因 kb_card 表的所有权在 kb 模块——
     * 跨模块访问通过 service，避免 topic 直接依赖 KbCardMapper（缓解耦合 + 后续改 schema 不波及调用方）。
     */
    public java.util.Optional<BMatch> findBestBMatch(long userId, float[] vec) {
        List<BMatch> matches = kbCardMapper.findBestBMatches(userId, vec, 0.25, 1);
        return matches.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(matches.get(0));
    }

    /** 有 B 层卡片的用户 id 列表（{@link com.sks.topic.HotTopicJob} 按 user 轮询热点打分用）。 */
    public List<Long> findUserIdsWithBCards() {
        return kbCardMapper.findUserIdsWithBCards();
    }

    // ---- 补卡 supplement / confirm（Task 1.5）--------------------------------

    /**
     * 补卡第一步：调 card_gen 抽卡 + 缺口 + 冲突检测。
     *
     * <p>两步流程的第一步（设计文档 §7 + §11.4）：
     * <ol>
     *   <li>调 {@link AiClient#cardGen}：raw_text 先在 Python 侧过 UGC 安全（§5.1），命中
     *       {@code blocked=true} → 抛 {@link ErrorCode#CONTENT_BLOCKED}（补卡免费，无退款编排，直接抛）。
     *   <li><b>无冲突</b> → 直接建卡（B 层同步算 embedding，复用 {@link #create}）。{@code createdIds}
     *       非空，{@code conflicts} 为空。
     *   <li><b>有冲突</b> → 不建任何卡，返回 {@code cards/gaps/conflicts} 供前端展示冲突 + 二次确认。
     *       {@code createdIds=null}，{@link SupplementResult#needsConfirm()} 返回 true。
     * </ol>
     *
     * <p><b>免费</b>：brief 不列扣额度/退款——补卡是 FREE（与 rewrite_sentence 同档），不走 credit 链。
     *
     * <p>建卡复用 {@link #create}：每张卡 title+content 再过一次 safetyCheck（belt-and-suspenders，
     * raw_text 已过审但 LLM 抽出的卡内容仍按 UGC 标准复检）+ B 层 embed。IDOR 由 {@link #create}
     * 的 userId 隔离保证。
     */
    @Transactional
    public SupplementResult supplement(long userId, String rawText, String layer) {
        AiClient.CardGenResult result = aiClient.cardGen(userId, rawText, layer);
        if (result.blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        List<AiClient.CardGenConflict> conflicts =
                result.conflicts() == null ? List.of() : result.conflicts();
        List<String> gaps = result.gaps() == null ? List.of() : result.gaps();
        List<AiClient.CardGenCard> cards = result.cards() == null ? List.of() : result.cards();
        if (!conflicts.isEmpty()) {
            // 有冲突 → 返回供确认，不建任何卡
            return new SupplementResult(null, cards, gaps, conflicts);
        }
        // 无冲突 → 直接建卡（B 层同步 embed）
        List<Long> createdIds = new ArrayList<>();
        for (AiClient.CardGenCard c : cards) {
            createdIds.add(create(userId, layer, c.cardType(), c.title(), c.content().toString()));
        }
        return new SupplementResult(createdIds, cards, gaps, conflicts);
    }

    /**
     * 补卡第二步：用户确认后落卡（覆盖选中的冲突卡 + 建非冲突卡）。
     *
     * <p><b>无状态、不重新抽卡</b>：cards + conflicts 由前端原样回传（来自 supplement 响应），
     * 避免 re-extract 的 LLM 非确定性导致用户确认的卡 ≠ 实际落库的卡。Java 不再调 cardGen。
     *
     * <p>对每张新卡（按 index）：
     * <ul>
     *   <li>conflicts 中有 {@code card_index == i} 且其 {@code card_id ∈ overwriteCardIds} →
     *       <b>覆盖</b>该旧卡：先归档旧 content 到 {@code card_history}（§11.4）+ 更新
     *       （B 层重算 embedding，复用 {@link #update} 的归档+重算路径）。IDOR 由
     *       {@link KbCardMapper#findById} 的 user_id 过滤保证。
     *   <li>conflicts 中有但 {@code card_id ∉ overwriteCardIds} → <b>跳过</b>（用户选择不覆盖也不新建）。
     *   <li>无冲突 → <b>新建</b>（复用 {@link #create}，B 层同步 embed）。
     * </ul>
     *
     * <p>覆盖路径走 {@link #update}：safetyCheck 新值（confirm 回传的 cards 无服务端来源证明，
     * 故新值必须复审）→ 归档旧值到 {@code card_history}（PRD §11.4）→ 重算 B 层 embed → 更新。
     */
    @Transactional
    public ConfirmResult confirmSupplement(
            long userId,
            String layer,
            List<AiClient.CardGenCard> cards,
            List<AiClient.CardGenConflict> conflicts,
            List<Long> overwriteCardIds) {
        Set<Long> overwrite = overwriteCardIds == null ? Set.of() : new HashSet<>(overwriteCardIds);
        List<Long> createdIds = new ArrayList<>();
        List<Long> overwrittenIds = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            AiClient.CardGenCard c = cards.get(i);
            AiClient.CardGenConflict conflict = findConflict(conflicts, i);
            if (conflict != null && overwrite.contains(conflict.cardId())) {
                // 覆盖：归档旧值 + 更新（B 层重算 embedding + safetyCheck）
                long oldId = conflict.cardId();
                update(userId, oldId, c.title(), c.content().toString());
                overwrittenIds.add(oldId);
            } else if (conflict != null) {
                // 跳过：用户未选覆盖
                continue;
            } else {
                // 无冲突 → 新建（B 层同步 embed + safetyCheck）
                createdIds.add(create(userId, layer, c.cardType(), c.title(), c.content().toString()));
            }
        }
        return new ConfirmResult(createdIds, overwrittenIds);
    }

    private static AiClient.CardGenConflict findConflict(
            List<AiClient.CardGenConflict> conflicts, int cardIndex) {
        if (conflicts == null) return null;
        for (AiClient.CardGenConflict c : conflicts) {
            if (c.cardIndex() == cardIndex) return c;
        }
        return null;
    }

    /** 补卡 supplement 结果。{@code createdIds} 非空=已建卡；为 null=需确认（有冲突）。 */
    public record SupplementResult(
            List<Long> createdIds,
            List<AiClient.CardGenCard> cards,
            List<String> gaps,
            List<AiClient.CardGenConflict> conflicts) {
        /** 有冲突待用户确认（true=前端应展示冲突 + 调 confirm）。 */
        public boolean needsConfirm() {
            return conflicts != null && !conflicts.isEmpty();
        }
    }

    /** 补卡 confirm 结果：新建卡 id + 覆盖的旧卡 id。 */
    public record ConfirmResult(List<Long> createdIds, List<Long> overwrittenIds) {}
}
