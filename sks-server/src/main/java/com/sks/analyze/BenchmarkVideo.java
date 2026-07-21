package com.sks.analyze;

import java.time.OffsetDateTime;

/**
 * 拆账号 TOP20 明细行（表 {@code benchmark_video}，Flyway V1 已建）。
 *
 * <p>Python 拆账号逐条产出：标题 / 播放 / 收藏 / 完整转写 / 结构化拆解（JSONB）。逐条「深拆/仿写」
 * 按钮为 V1.1，本表行已为其预留（design §3）。MVP 仅展示。
 */
public class BenchmarkVideo {

    private Long id;
    private Long analyzeTaskId;
    private String title;
    private Long playCount;
    private Long favCount;
    private String transcript;
    private String structure; // JSONB 文本
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAnalyzeTaskId() {
        return analyzeTaskId;
    }

    public void setAnalyzeTaskId(Long analyzeTaskId) {
        this.analyzeTaskId = analyzeTaskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getPlayCount() {
        return playCount;
    }

    public void setPlayCount(Long playCount) {
        this.playCount = playCount;
    }

    public Long getFavCount() {
        return favCount;
    }

    public void setFavCount(Long favCount) {
        this.favCount = favCount;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getStructure() {
        return structure;
    }

    public void setStructure(String structure) {
        this.structure = structure;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
