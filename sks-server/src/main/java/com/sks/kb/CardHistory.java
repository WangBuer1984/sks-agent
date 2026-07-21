package com.sks.kb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 卡片历史归档实体（表 {@code card_history}）。
 *
 * <p>B 层卡片编辑内容时，把<b>旧</b> content 归档到这里（设计 §3「编辑 B 层内容时旧值写 card_history」），
 * 供追溯。{@code old_content} 为 JSONB，Java 侧以 String（JSON 文本）承载，Mapper 用 {@code ::jsonb} cast。
 * A/C 层编辑不归档（brief 字面：仅 B 层内容编辑触发归档）。
 *
 * <p>表无 {@code deleted} 列，全局逻辑删除配置对本实体不生效。
 */
@TableName("card_history")
public class CardHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cardId;
    private String oldContent;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public String getOldContent() {
        return oldContent;
    }

    public void setOldContent(String oldContent) {
        this.oldContent = oldContent;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
