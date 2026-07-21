package com.sks.kb;

/**
 * B 层卡片的热点匹配命中结果（{@link KbCardMapper#findBestBMatches} 投影）。
 *
 * <p>{@code distance} 为 pgvector 余弦<b>距离</b>（{@code embedding <=> query}，0=相同, 2=相反），
 * <b>不是</b>相似度；{@code similarity = 1 - distance}（仅打分日志用，不入库）。
 *
 * <p>列名下划线（{@code card_type}）由 MyBatis {@code map-underscore-to-camel-case} 自动映射到
 * record 组件（{@code cardType}）——与 {@link CardSummary} 同模式。
 */
public record BMatch(Long id, String cardType, String title, Double distance) {}
