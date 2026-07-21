package com.sks.review;

/**
 * {@link ReviewStateMachine#classify} 的输入上下文——仅 {@code tracking + PLAY_COUNT} 迁移需要。
 *
 * <p>纯 record，无 Spring 依赖——让 {@link ReviewStateMachine} 保持可作纯单元测试（不读配置、不触 DB）。
 * 阈值由 {@link ReviewService} 从配置（{@code sks.review.hot-threshold} / {@code sks.review.flop-threshold}）读后
 * 传入；avg 由 {@link com.sks.script.ScriptMapper#avgPlayCount30d} 查近 30 天已复盘稿播放量均值后传入。
 *
 * @param playCount     用户填入的播放量（data_source=manual，MVP 用户手填；V1.1 auto-scrape 复用状态机不变）
 * @param avg30d        近 30 天 hot/plain/flop 稿的播放量均值（baseline）；{@code <=0} 视为无历史 → plain
 * @param hotThreshold  热款上界倍数（默认 3.0，可配）
 * @param flopThreshold 扑街下界倍数（默认 0.5，可配）
 */
public record ReviewContext(long playCount, double avg30d, double hotThreshold, double flopThreshold) {}
