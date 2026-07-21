package com.sks.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 定位档案实体（表 {@code positioning_profile}，Flyway V1）。
 *
 * <p>每用户可有多条历史版本（{@code version} 递增），同一时刻<b>至多一条</b> {@code active=true}。
 * 校准生效（{@link ProfileService#confirm}）时：旧 active 行置 {@code active=false}（留历史）+ 插入新行
 * {@code active=true, version=max+1}。再校准即重复该流程（见 {@code reCalibrationKeepsOldVersionInactive}）。
 *
 * <p>{@code content} 为 JSONB（prompt 驱动、迭代频繁，存 JSONB 不开关系表——设计 §3），Java 侧以 String
 * （JSON 文本）承载，Mapper 用 {@code #{content}::jsonb} 显式 cast（与 {@code kb_card.content} 同模式）。
 */
@TableName("positioning_profile")
public class PositioningProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String content;
    private Integer version;
    private Boolean active;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
