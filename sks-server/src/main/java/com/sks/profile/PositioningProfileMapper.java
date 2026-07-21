package com.sks.profile;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 定位档案 Mapper。
 *
 * <p><b>自定义 SQL</b>（不用 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} 自动方法）——
 * 与 {@code kb_card} 同模式：显式控制 {@code content::jsonb} cast + {@code active} 翻转语义，
 * 避免全局逻辑删除配置干扰（{@code positioning_profile} 无 {@code deleted} 列，但保持一致风格）。
 *
 * <p>{@code content} 列为 JSONB，用 {@code #{content}::jsonb} 显式 cast（与 {@code kb_card.content} 同）。
 */
@Mapper
public interface PositioningProfileMapper {

    /**
     * 插入档案行。{@code @Options(useGeneratedKeys=true)} 把 BIGSERIAL 生成的 id 回填到实体。
     */
    @Insert(
            "INSERT INTO positioning_profile(user_id, content, version, active) "
                    + "VALUES(#{userId}, #{content}::jsonb, #{version}, #{active})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PositioningProfile profile);

    /**
     * 把当前用户的旧 active 行翻 {@code active=false}（留历史，不删）。
     *
     * <p>confirm 流程的第一步——在插入新 active 行<b>之前</b>执行，与 insert 同事务，保证同一时刻至多一条 active。
     */
    @Update(
            "UPDATE positioning_profile SET active = false "
                    + "WHERE user_id = #{uid} AND active = true")
    int deactivateActive(@Param("uid") long userId);

    /**
     * 取当前用户的 active 档案（至多一条）。无 active（未校准过）返回 null。
     *
     * <p>供 {@link ProfileService#activeProfile} 读 content JSONB 注入 script_gen，与
     * {@link com.sks.script.ScriptService} 的 P1 空桩对接。
     */
    @Select(
            "SELECT id, user_id, content, version, active, created_at "
                    + "FROM positioning_profile WHERE user_id = #{uid} AND active = true "
                    + "ORDER BY version DESC LIMIT 1")
    PositioningProfile findActive(@Param("uid") long userId);

    /** 当前用户的档案总行数（含历史 inactive）——再校准用例断言「两次 confirm → 两条行」用。 */
    @Select("SELECT COUNT(*) FROM positioning_profile WHERE user_id = #{uid}")
    int countByUser(@Param("uid") long userId);

    /** 当前用户的 active 档案数——再校准用例断言「只有一条 active」用（恒为 0 或 1）。 */
    @Select("SELECT COUNT(*) FROM positioning_profile WHERE user_id = #{uid} AND active = true")
    int countActiveByUser(@Param("uid") long userId);

    /**
     * 当前用户的最大版本号（无档案返回 0）。confirm 插新行时 {@code version = maxVersion + 1}，
     * 在 {@code deactivateActive} 之后调用（deactivate 不动 version，故 max 仍取自所有历史行）。
     */
    @Select("SELECT COALESCE(MAX(version), 0) FROM positioning_profile WHERE user_id = #{uid}")
    int maxVersion(@Param("uid") long userId);
}
