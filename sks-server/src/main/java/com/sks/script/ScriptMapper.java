package com.sks.script;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 稿件 Mapper。
 *
 * <p><b>自定义 SQL</b>——hook/body/cta 是 JSONB 列，{@link BaseMapper} 自动拼的 INSERT / UPDATE 不带
 * {@code ::jsonb} cast，PG 报 {@code column is of type jsonb but expression is of type character varying}。
 * 故全部写 / 改走自定义 SQL + {@code #{...}::jsonb} 显式 cast（与 {@link com.sks.kb.KbCardMapper} 同模式）。
 */
@Mapper
public interface ScriptMapper extends BaseMapper<Script> {

    /**
     * 插占位行（{@code review_state='generating'}，hook/body/cta 为 null）——§4.1 扣费前先建占位行拿
     * 稳定 {@code script_id} 作退款幂等键。{@code @Options(useGeneratedKeys=true)} 回填 id。
     */
    @Insert(
            "INSERT INTO script(user_id, topic_id, platform, review_state) "
                    + "VALUES(#{userId}, #{topicId}, #{platform}, 'generating')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlaceholder(Script script);

    /**
     * 回填：写 hook/body/cta（JSONB）+ 置 {@code review_state='draft'} + updated_at。生成成功路径调。
     */
    @Update(
            "UPDATE script SET hook = #{hook}::jsonb, body = #{body}::jsonb, cta = #{cta}::jsonb, "
                    + "review_state = 'draft', updated_at = now() WHERE id = #{id}")
    int backfill(
            @Param("id") long id,
            @Param("hook") String hook,
            @Param("body") String body,
            @Param("cta") String cta);

    /** 置占位行 {@code review_state='failed'}（生成失败路径调，退款前）。 */
    @Update("UPDATE script SET review_state = 'failed', updated_at = now() WHERE id = #{id}")
    int markFailed(@Param("id") long id);

    /**
     * 同选题同平台是否已有成功稿（§4.2 免扣）：{@code review_state NOT IN ('generating','failed') AND platform=?}。
     * 命中 → 返回已有 id，免扣免调（同平台短路）。无则返回 null（交由调用方判断是否走切平台再生成路径）。
     */
    @Select(
            "SELECT id FROM script WHERE user_id = #{userId} AND topic_id = #{topicId} "
                    + "AND review_state NOT IN ('generating','failed') AND platform = #{platform} "
                    + "ORDER BY id DESC LIMIT 1")
    Long findSuccessfulId(
            @Param("userId") long userId, @Param("topicId") long topicId, @Param("platform") String platform);

    /**
     * 同选题是否已<b>在任意平台</b>成功过（design §3 line 121-122「切平台再生成、同选题不加扣」）：
     * 命中 → 本次为切平台再生成，免扣（选题已成功过，再生成免费；省约 2/3 token 的前提是再生成本就发生）。
     * 仅作存在性判定；具体同平台短路走 {@link #findSuccessfulId} 三参重载。
     */
    @Select(
            "SELECT id FROM script WHERE user_id = #{userId} AND topic_id = #{topicId} "
                    + "AND review_state NOT IN ('generating','failed') ORDER BY id DESC LIMIT 1")
    Long findSuccessfulIdAnyPlatform(@Param("userId") long userId, @Param("topicId") long topicId);

    /**
     * 取稿件（含 hook/body/cta JSONB 文本）。带 user_id 过滤（IDOR 防护，§5.1）——跨用户返回 null。
     */
    @Select("SELECT * FROM script WHERE id = #{id} AND user_id = #{userId}")
    Script findById(@Param("id") long id, @Param("userId") long userId);

    /** 当前用户的稿件列表（可选 review_state 过滤），按更新时间倒序。 */
    @Select(
            "SELECT id, user_id, topic_id, platform, review_state, created_at, updated_at FROM script "
                    + "WHERE user_id = #{userId} AND (#{state} IS NULL OR review_state = #{state}) "
                    + "ORDER BY updated_at DESC, id DESC")
    List<Script> listByUser(@Param("userId") long userId, @Param("state") String state);

    /**
     * 单句手改：重写某段（hook/body/cta）的 JSONB 整列。{@code section} 已由 service 校验 ∈ {hook,body,cta}，
     * 用 {@code ${section}} 拼列名（受控、无注入风险）。带 user_id 过滤（IDOR）。
     */
    @Update(
            "UPDATE script SET ${section} = #{json}::jsonb, updated_at = now() "
                    + "WHERE id = #{id} AND user_id = #{userId}")
    int updateSection(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("section") String section,
            @Param("json") String json);
}
