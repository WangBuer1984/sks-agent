package com.sks.topic;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 选题实体（表 {@code topic}）。
 *
 * <p>四路来源（design §3）：hot / faq / benchmark / replay——MVP 期四路自动抓取系统为 P2，
 * 本任务仅支持用户自建（source=faq）。字段 {@code status} 默认 'open'，选题被生成稿后不改状态
 * （同选题可多次生成 / 换角度，§4.2 免扣逻辑在 script 侧判断）。
 */
@TableName("topic")
public class Topic {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String source;
    private String title;
    private String rationale;
    private String pillar;
    private String status;
    private OffsetDateTime createdAt;

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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getPillar() {
        return pillar;
    }

    public void setPillar(String pillar) {
        this.pillar = pillar;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
