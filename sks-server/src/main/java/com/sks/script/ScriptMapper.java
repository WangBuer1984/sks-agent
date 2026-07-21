package com.sks.script;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
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
     * 取用户的<b>历史成功稿</b>（{@code review_state NOT IN ('generating','failed')}，含 draft 与复盘各态）——
     * 供 {@link DedupChecker#findSimilar} SimHash 查重比对。取 id + hook + body + cta（全文信号，非仅 body）。
     * 排除刚创建稿由调用方处理（传 excludeScriptId）。纯读、无写、不阻断（PRD §11.2）。
     */
    @Select(
            "SELECT id, hook, body, cta FROM script WHERE user_id = #{userId} "
                    + "AND review_state NOT IN ('generating','failed') ORDER BY id DESC")
    List<Script> findSuccessfulForDedup(@Param("userId") long userId);

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

    // ---- 复盘状态机迁移（§4.4，Task 4.2）----
    // 每个 UPDATE 带 review_state 守卫，确保纯函数 next() 校验后到 DB 落库期间态未被并发改；
    // rows==0 → 并发改了态，调用方（ReviewService）抛 PARAM_INVALID。

    /** draft → pending（采用）。守卫 review_state='draft'。 */
    @Update(
            "UPDATE script SET review_state = 'pending', updated_at = now() "
                    + "WHERE id = #{id} AND user_id = #{userId} AND review_state = 'draft'")
    int markPending(@Param("id") long id, @Param("userId") long userId);

    /** pending → tracking（登记发布链接）+ 写 publish_url。守卫 review_state='pending'。 */
    @Update(
            "UPDATE script SET review_state = 'tracking', publish_url = #{url}, updated_at = now() "
                    + "WHERE id = #{id} AND user_id = #{userId} AND review_state = 'pending'")
    int markTracking(
            @Param("id") long id, @Param("userId") long userId, @Param("url") String url);

    /**
     * tracking → hot/plain/flop（填播放量后判态）+ 写 play_count + data_source='manual'。
     * 守卫 review_state='tracking'。state 由 {@link com.sks.review.ReviewStateMachine#classify} 决定。
     */
    @Update(
            "UPDATE script SET review_state = #{state}, play_count = #{playCount}, "
                    + "data_source = 'manual', updated_at = now() "
                    + "WHERE id = #{id} AND user_id = #{userId} AND review_state = 'tracking'")
    int markReviewState(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("state") String state,
            @Param("playCount") int playCount);

    /**
     * 近 30 天已复盘稿（hot/plain/flop 且 play_count 非空）的播放量均值（§4.4「近30天均值」baseline）。
     *
     * <p>无历史 → AVG 返回 NULL → COALESCE → 0；{@link com.sks.review.ReviewStateMachine#classify}
     * 把 avg&lt;=0 视为无 baseline → plain（首条稿无历史可比对）。
     */
    @Select(
            "SELECT COALESCE(AVG(play_count), 0) FROM script WHERE user_id = #{userId} "
                    + "AND play_count IS NOT NULL AND review_state IN ('hot','plain','flop') "
                    + "AND created_at >= now() - interval '30 days'")
    double avgPlayCount30d(@Param("userId") long userId);

    // ---- RejectSweeper（§4.4 48h 未采用 draft → rejected）----

    /** 48h 前仍未采用的 draft 稿 id 列表（RejectSweeper 扫描）。<b>仅 draft</b>——pending 不被扫。 */
    @Select("SELECT id FROM script WHERE review_state = 'draft' AND created_at < #{cutoff}")
    List<Long> findDraftIdsOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    /** draft → rejected（RejectSweeper）。守卫 review_state='draft'——幂等，重复扫对已 rejected 行 no-op。 */
    @Update("UPDATE script SET review_state = 'rejected', updated_at = now() WHERE id = #{id} AND review_state = 'draft'")
    int markRejected(@Param("id") long id);
}
