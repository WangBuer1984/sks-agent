package com.sks.kb;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识库卡片 Mapper。
 *
 * <p><b>所有方法均为自定义 SQL</b>——不用 {@link BaseMapper} 的自动方法（{@code selectList} /
 * {@code updateById} / {@code deleteById}），因为全局逻辑删除配置 {@code logic-delete-value:1}（int）
 * 与 {@code kb_card.deleted}（BOOLEAN）类型不匹配，自动拼接的 {@code WHERE deleted=0} 会在 PG 报错。
 * 自定义 SQL 显式写 {@code WHERE deleted=false} / {@code SET deleted=true}，绕过该问题。
 *
 * <p>{@code content} 列为 JSONB，用 {@code #{content}::jsonb} 显式 cast；{@code embedding} 列为
 * {@code vector(1024)}，用 {@code #{embedding, typeHandler=com.sks.kb.VectorTypeHandler}::vector} 让
 * TypeHandler 把 {@code float[]} 转成 pgvector 字面量后 PG 自动解析。
 */
@Mapper
public interface KbCardMapper extends BaseMapper<KbCard> {

    /**
     * 新建卡片。{@code embedding} 为 null（A/C 层）时 TypeHandler 走 {@code setNull}，PG 写 NULL。
     * {@code @Options(useGeneratedKeys=true)} 把 BIGSERIAL 生成的 id 回填到实体。
     */
    @Insert(
            "INSERT INTO kb_card(user_id, layer, card_type, title, content, embedding) "
                    + "VALUES(#{userId}, #{layer}, #{cardType}, #{title}, "
                    + "#{content}::jsonb, #{embedding, typeHandler=com.sks.kb.VectorTypeHandler}::vector)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCard(KbCard card);

    /**
     * 取卡片（含旧 content，供编辑时归档 + 比对变化）。不带 embedding——编辑路径会重算，无需回读。
     *
     * <p><b>带 user_id 过滤</b>（IDOR 防护，设计 §5.1）：跨用户访问返回 null，
     * 由 {@link KbCardService#update} 翻译为 {@code PARAM_INVALID}——不泄露「存在但不属于你」。
     */
    @Select(
            "SELECT id, user_id, layer, card_type, title, content, deleted, created_at, updated_at "
                    + "FROM kb_card WHERE id = #{id} AND user_id = #{userId} AND deleted = false")
    KbCard findById(@Param("id") long id, @Param("userId") long userId);

    /**
     * 更新 B 层卡片：写 title / content / embedding（已重算）+ updated_at。{@code deleted=false} 守卫
     * 防止误改已删卡。
     */
    @Update(
            "UPDATE kb_card SET title = #{title}, content = #{content}::jsonb, "
                    + "embedding = #{embedding, typeHandler=com.sks.kb.VectorTypeHandler}::vector, "
                    + "updated_at = now() WHERE id = #{id} AND deleted = false")
    int updateWithEmbedding(
            @Param("id") long id,
            @Param("title") String title,
            @Param("content") String content,
            @Param("embedding") float[] embedding);

    /** 更新 A/C 层卡片：只写 title / content + updated_at，不动 embedding。 */
    @Update(
            "UPDATE kb_card SET title = #{title}, content = #{content}::jsonb, updated_at = now() "
                    + "WHERE id = #{id} AND deleted = false")
    int updateNoEmbedding(
            @Param("id") long id,
            @Param("title") String title,
            @Param("content") String content);

    /**
     * 软删：手动 SET deleted=true（不用 @TableLogic，绕开 int/boolean 不匹配）。
     *
     * <p><b>带 user_id 过滤</b>（IDOR 防护）：跨用户删除影响 0 行，由 {@link KbCardService#delete}
     * 在前置 {@link #findById} 所有权校验后调用；返回 rows-affected 供二次断言。
     */
    @Update(
            "UPDATE kb_card SET deleted = true, updated_at = now() "
                    + "WHERE id = #{id} AND user_id = #{userId} AND deleted = false")
    int softDelete(@Param("id") long id, @Param("userId") long userId);

    /** 当前用户的未删卡片数（UGC 安全拦截后断言「什么都没落库」用）。 */
    @Select("SELECT COUNT(*) FROM kb_card WHERE user_id = #{uid} AND deleted = false")
    int countByUser(@Param("uid") long userId);

    /**
     * 当前用户某层的未删卡片数（Task 2.2 定位校准 confirm 后断言「A 层卡数 = summarize 产出的 a_cards 数」用）。
     *
     * <p>{@code layer} 为 {@code 'A'/'B'/'C'}（CHAR(1)），与 {@link #listByUser} 的 layer 过滤同语义。
     */
    @Select("SELECT COUNT(*) FROM kb_card WHERE user_id = #{uid} AND layer = #{layer} AND deleted = false")
    int countByLayer(@Param("uid") long userId, @Param("layer") String layer);

    /**
     * 列出当前用户的未删卡片（可选 layer 过滤）。<b>不返回 embedding</b>（1024 float 太大，前端不需要）。
     * 用 {@link CardSummary} 轻量投影——列名下划线由 map-underscore-to-camel-case 自动映射到 record 组件。
     */
    @Select(
            "SELECT id, layer, card_type, title, content, updated_at FROM kb_card "
                    + "WHERE user_id = #{uid} AND deleted = false "
                    + "AND (#{layer} IS NULL OR layer = #{layer}) "
                    + "ORDER BY updated_at DESC, id DESC")
    List<CardSummary> listByUser(@Param("uid") long userId, @Param("layer") String layer);

    /**
     * 在当前用户的 B 层卡片上做 pgvector 余弦匹配（热点打分用，Task 1.7）——镜像 Python
     * {@code rag/retrieve.py#retrieve_b_cards} 的 SQL：{@code user_id + layer='B' + deleted=false}
     * + 距离阈值 + ORDER BY 距离 + LIMIT k。<b>不返回 content</b>（打分只需 id/title/distance）。
     *
     * <p>方向注意：{@code embedding <=> vec} 返回余弦<b>距离</b>（0=相同），{@code <= maxDistance}
     * 相当于 {@code similarity >= 1 - maxDistance}；{@code maxDistance=0.25} = 相似度 {@code >= 0.75}。
     * 照抄 retrieve.py 勿写反（最常见的 RAG 翻车点）。
     *
     * <p>{@code #{vec, typeHandler=com.sks.kb.VectorTypeHandler}::vector} 与 {@link #insertCard} 同模式：
     * TypeHandler 把 {@code float[]} 拼成 pgvector 字面量字符串，{@code ::vector} 显式 cast。
     * 同一参数复用 3 次（SELECT 投影 + WHERE + ORDER BY）——MyBatis 各占位符独立绑定为同值字符串。
     */
    @Select(
            "SELECT id, card_type, title, "
                    + "(embedding <=> #{vec, typeHandler=com.sks.kb.VectorTypeHandler}::vector) AS distance "
                    + "FROM kb_card "
                    + "WHERE user_id = #{uid} AND layer = 'B' AND deleted = false "
                    + "AND (embedding <=> #{vec, typeHandler=com.sks.kb.VectorTypeHandler}::vector) <= #{maxDistance} "
                    + "ORDER BY embedding <=> #{vec, typeHandler=com.sks.kb.VectorTypeHandler}::vector "
                    + "LIMIT #{k}")
    List<BMatch> findBestBMatches(
            @Param("uid") long userId,
            @Param("vec") float[] vec,
            @Param("maxDistance") double maxDistance,
            @Param("k") int k);

    /** 有 B 层未删卡片的用户 id 去重列表（{@link com.sks.topic.HotTopicJob} 按 user 轮询热点打分用）。 */
    @Select("SELECT DISTINCT user_id FROM kb_card WHERE layer = 'B' AND deleted = false")
    List<Long> findUserIdsWithBCards();
}
