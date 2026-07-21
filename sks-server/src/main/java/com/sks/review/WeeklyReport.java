package com.sks.review;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 周归因报告实体（表 {@code weekly_report}，§4.4 / design §3）。
 *
 * <p>{@code (user_id, week_start)} UNIQUE——{@link WeeklyReportJob} 重跑同周 upsert 不重复插行。
 * {@code content} 为 JSONB（{@code {summary, wins, gaps, next_focus}} 四段，或 {@code {blocked:true}} 兜底），
 * Java 侧以 String（JSON 文本）承载——与 {@code script.hook} 同模式，Mapper 用 {@code #{content}::jsonb} cast。
 */
@TableName("weekly_report")
public class WeeklyReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate weekStart;
    private String content;
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

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public void setWeekStart(LocalDate weekStart) {
        this.weekStart = weekStart;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
