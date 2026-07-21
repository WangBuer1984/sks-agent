package com.sks.kb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 知识库卡片实体（表 {@code kb_card}）。
 *
 * <p>三层结构（设计 §3）：
 *
 * <ul>
 *   <li><b>A 层</b>（人物画像）：{@code layer='A'}，{@code embedding} 为 null（不做语义检索）。
 *   <li><b>B 层</b>（产品 / 选题知识）：{@code layer='B'}，{@code embedding} 必填（§7.4 立即同步，
 *       供 Task 1.3 RAG 检索）。
 *   <li><b>C 层</b>（金句 / 素材）：{@code layer='C'}，{@code embedding} 为 null。
 * </ul>
 *
 * <p>{@code content} 为 JSONB（prompt 驱动、迭代频繁，存 JSONB 不开关系表——设计 §3 数据模型决策），
 * Java 侧以 String（JSON 文本）承载，Mapper 用 {@code #{content}::jsonb} 显式 cast。
 *
 * <p><b>逻辑删除 boolean/int 不匹配</b>：全局配置 {@code logic-delete-value:1}（int），但本表
 * {@code deleted} 列为 BOOLEAN——{@code deleted=0}（int）在 PG boolean 列上会报类型错。
 * 故<b>不</b>用 {@code @TableLogic}，也不用 {@code BaseMapper.selectList} 等自动拼 {@code deleted=0}
 * 的方法；所有查询 / 软删都用自定义 SQL 显式 {@code WHERE deleted=false} / {@code SET deleted=true}。
 *
 * <p>{@code embedding} 列读写由 {@link VectorTypeHandler} 处理（float[] ↔ pgvector 字面量）。
 */
@TableName("kb_card")
public class KbCard {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String layer;
    private String cardType;
    private String title;
    private String content;
    private float[] embedding;
    private Boolean deleted;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
