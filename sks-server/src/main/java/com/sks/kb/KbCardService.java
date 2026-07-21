package com.sks.kb;

import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.util.List;
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
}
