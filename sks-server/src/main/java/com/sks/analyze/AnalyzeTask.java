package com.sks.analyze;

import java.time.OffsetDateTime;

/**
 * 拆解任务实体（表 {@code analyze_task}，Flyway V1 已建——本任务不加迁移）。
 *
 * <p>状态机（§4.3 + Python Task 3.2 写）：
 * <ul>
 *   <li>{@code queued}：Java 建行后、Python 写 running 前。
 *   <li>{@code running}：Python BackgroundTasks 执行中（含 transcribe 心跳）。
 *   <li>{@code done}：全量成功（终态）。
 *   <li>{@code partial}：部分条目失败（<b>终态</b>，Python 不再更新）——Java 轮询按比例退款。
 *   <li>{@code failed}：全量失败 / 超时 / 停滞（终态）——Java 全额退款。
 * </ul>
 *
 * <p>{@code progress} 语义<b>钉死</b>：已完成条数 / 总条数 × 100（整数），<b>不是</b>阶段进度——
 * 按比例退款 {@code refundN = charged×(100-progress)/100} 的数学依赖此口径（schema 注释 + Task 3.2）。
 *
 * <p>{@code input} / {@code result} 为 JSONB 列，用字符串承载（mapper 处 {@code ::jsonb} cast），
 * 与 {@code script.hook} / {@code kb_card.content} 同模式——prompt-driven，迭代频繁，不拆关系表。
 */
public class AnalyzeTask {

    private Long id;
    private Long userId;
    private String taskType; // account / video
    private String status; // queued / running / done / partial / failed
    private Integer progress;
    private Integer charged;
    private String input; // JSONB 文本
    private String result; // JSONB 文本
    private String error;
    private OffsetDateTime updatedAt;
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

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public Integer getCharged() {
        return charged;
    }

    public void setCharged(Integer charged) {
        this.charged = charged;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
