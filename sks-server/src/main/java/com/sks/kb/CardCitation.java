package com.sks.kb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 卡片引用实体（表 {@code card_citation}）。
 *
 * <p>稿件生成时引用了某张卡片就插一行（{@code script_id} ↔ {@code card_id}）。
 * 删除卡片前查此表：有引用且非 force 时抛 {@link com.sks.common.ErrorCode#CARD_IN_USE}，
 * 要求二次确认（设计 §3 删除保护）。
 *
 * <p>引用<b>不</b>软删（无 {@code deleted} 列）；卡片软删后引用行仍在（历史溯源），故删除守卫不区分
 * 卡片是否已删——仅看 {@code card_id} 匹配数。
 *
 * <p>构造器 {@code (scriptId, cardId)} 便于测试直接插入引用行（brief 测试 {@code new CardCitation(999L, id)}）。
 */
@TableName("card_citation")
public class CardCitation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private Long cardId;
    private OffsetDateTime createdAt;

    public CardCitation() {}

    public CardCitation(Long scriptId, Long cardId) {
        this.scriptId = scriptId;
        this.cardId = cardId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getScriptId() {
        return scriptId;
    }

    public void setScriptId(Long scriptId) {
        this.scriptId = scriptId;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
