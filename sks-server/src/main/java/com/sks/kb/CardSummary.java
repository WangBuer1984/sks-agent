package com.sks.kb;

import java.time.OffsetDateTime;

/**
 * KB 卡片列表投影（不携带 embedding——前端列表不需要 1024 float，只详情 / 编辑才可能回拉）。
 *
 * <p>record 组件名按驼峰，{@code map-underscore-to-camel-case} 把 {@code card_type} → {@code cardType}、
 * {@code updated_at} → {@code updatedAt}。
 */
public record CardSummary(
        Long id, String layer, String cardType, String title, String content, OffsetDateTime updatedAt) {}
