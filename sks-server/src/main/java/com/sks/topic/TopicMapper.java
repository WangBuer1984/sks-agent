package com.sks.topic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 选题 Mapper。
 *
 * <p>{@link BaseMapper#insert} 可直接用于本实体（无非标准类型列，纯 varchar/text + timestamptz 默认值）。
 * 自定义 {@link #findById} 带 user_id 过滤（IDOR 防护，§5.1——不泄露「存在但不属于你」）。
 */
@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

    /** 按 id + user_id 取选题（IDOR 防护）；跨用户返回 null。 */
    @Select("SELECT * FROM topic WHERE id = #{id} AND user_id = #{userId}")
    Topic findById(@Param("id") long id, @Param("userId") long userId);

    /** 当前用户的全部选题（按创建时间倒序），供创作页选择。 */
    @Select("SELECT * FROM topic WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC")
    List<Topic> listByUser(@Param("userId") long userId);

    /**
     * 四路聚合查询（Task 1.7）：按 user_id（IDOR 隔离）+ 可选 source 过滤，按 pillar 排序。
     *
     * <p><b>pillar 排序 MVP 选择</b>（brief cross-task 决策 #2）：{@code pillar ASC NULLS LAST} 为主序
     * + {@code created_at DESC, id DESC} 为次序——即「按内容支柱分组（字母序），同支柱内最新优先，
     * 空 pillar 垫底」。真正的「支柱配比加权」需按支柱配额抽样（V1.1+），P1 用「分组 + 最新」既满足
     * 「按 pillar 聚拢」的可读性目标又不引入未定义的配比数据。PG 的 {@code NULLS LAST} 让空 pillar
     * 排到末尾（默认 ASC NULLS FIRST 在 PG 中反而是 NULLS LAST？——PG 默认 ASC=NULLS LAST，显式写明更稳）。
     *
     * <p>{@code (#{source} IS NULL OR source = #{source})}：source 为 null → 不过滤（聚合四路）；
     * source 非空 → 单路过滤。
     */
    @Select(
            "SELECT * FROM topic WHERE user_id = #{userId} "
                    + "AND (#{source}::text IS NULL OR source = #{source}) "
                    + "ORDER BY pillar ASC NULLS LAST, created_at DESC, id DESC")
    List<Topic> listByUserWithSource(@Param("userId") long userId, @Param("source") String source);

    /**
     * 按 {@code (user_id, source, title)} 计数——拆账号 done 后写 {@code source='benchmark'} 选题的
     * 幂等去重守卫（Task 3.3）。{@code topic} 表无 task_id 外链，故以标题为去重键；轮询器重复
     * reconcile 时命中已存即跳过，不双插。
     */
    @Select(
            "SELECT COUNT(*) FROM topic WHERE user_id = #{userId} AND source = #{source} AND title = #{title}")
    int countByUserSourceTitle(
            @Param("userId") long userId, @Param("source") String source, @Param("title") String title);
}
