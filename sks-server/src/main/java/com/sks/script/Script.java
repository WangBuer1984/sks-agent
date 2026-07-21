package com.sks.script;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;

/**
 * 稿件实体（表 {@code script}）。
 *
 * <p>三段 {@code hook/body/cta} 为 JSONB（每段是 {@code {sentences:[{idx,text}]}} 句数组，design §3），
 * Java 侧以 String（JSON 文本）承载——与 {@link com.sks.kb.KbCard#getContent} 同模式，Mapper 用
 * {@code #{hook}::jsonb} 显式 cast。逐句编辑（单句手改 / AI 重写）的数据基础即此结构。
 *
 * <p>{@code reviewState} 生成期前置态 {@code generating/failed}（扣费前先插占位行拿 id，§4.1），
 * 复盘七态 {@code draft/pending/tracking/hot/plain/flop/rejected}（§4.4 状态机）。本任务仅触及
 * {@code generating → draft/failed} 的流转，复盘状态机在后续 task。
 */
@TableName("script")
public class Script {

    private static final ObjectMapper OM = new ObjectMapper();

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long topicId;
    private String hook;
    private String body;
    private String cta;
    private String platform;
    private String reviewState;
    private String publishUrl;
    private Integer playCount;
    private String dataSource;
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

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public String getHook() {
        return hook;
    }

    public void setHook(String hook) {
        this.hook = hook;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getCta() {
        return cta;
    }

    public void setCta(String cta) {
        this.cta = cta;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getReviewState() {
        return reviewState;
    }

    public void setReviewState(String reviewState) {
        this.reviewState = reviewState;
    }

    public String getPublishUrl() {
        return publishUrl;
    }

    public void setPublishUrl(String publishUrl) {
        this.publishUrl = publishUrl;
    }

    public Integer getPlayCount() {
        return playCount;
    }

    public void setPlayCount(Integer playCount) {
        this.playCount = playCount;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
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

    // ---- 逐句访问辅助（供 service 取句、测试断言）----

    /**
     * 取 {@code section}（hook/body/cta）中 {@code idx} 对应的单句文本。
     *
     * <p>解析 {@code {sentences:[{idx,text}]}} 找到 {@code idx} 匹配项的 {@code text}。无段 / 无句 /
     * 无匹配返回 null。
     */
    public String sentence(String section, int idx) {
        String json = switch (section) {
            case "hook" -> hook;
            case "body" -> body;
            case "cta" -> cta;
            default -> null;
        };
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OM.readTree(json);
            JsonNode sentences = root.path("sentences");
            if (!sentences.isArray()) {
                return null;
            }
            for (JsonNode s : sentences) {
                if (s.path("idx").asInt(-1) == idx) {
                    String text = s.path("text").asText(null);
                    return text;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** {@link #sentence}(section, idx) + idx=0 的便捷方法（测试 {@code bodySentence(0)}）。 */
    public String bodySentence(int idx) {
        return sentence("body", idx);
    }
}
