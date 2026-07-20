package com.sks.user;

/**
 * {@code PUT /api/user/me} 的请求体：所有可更新字段（PRD §4.3），全部可空——null 表示不改该字段。
 *
 * <p>包含：
 *
 * <ul>
 *   <li>基础资料：nickname / gender / age / city（city 会注入生成做本地化选题）
 *   <li>创作资料：industry / identity / style / weeklyGoal（参与 completeness 计算）
 *   <li>主平台：defaultPlatform（PRD §4.2「默认生成用户主平台版本」）
 * </ul>
 *
 * <p>{@link #of} 是测试便捷构造器，只填 5 个创作资料字段（与 completeness 口径一致）。
 * 手机号换绑 MVP 不做（PRD §4.3 列了但优先级低，留 V1.1）——故本 DTO 不含 phone。
 */
public record UpdateMe(
        String nickname,
        String gender,
        Integer age,
        String city,
        String industry,
        String identity,
        String style,
        Integer weeklyGoal,
        String defaultPlatform) {

    /**
     * 测试便捷构造器：只填 5 个创作资料字段（nickname/industry/identity/style/weeklyGoal），
     * 其余置 null——便于在测试里直接表达「5 字段填 N 个」的 completeness 场景。
     */
    public static UpdateMe of(
            String nickname, String industry, String identity, String style, Integer weeklyGoal) {
        return new UpdateMe(nickname, null, null, null, industry, identity, style, weeklyGoal, null);
    }
}
