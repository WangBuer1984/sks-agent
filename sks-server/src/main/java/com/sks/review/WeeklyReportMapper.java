package com.sks.review;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 周归因报告 Mapper。
 *
 * <p><b>自定义 SQL</b>——{@code content} 是 JSONB 列，{@link BaseMapper} 自动拼的 INSERT 不带
 * {@code ::jsonb} cast，PG 报类型不匹配。故写走自定义 SQL + {@code #{...}::jsonb} 显式 cast（与
 * {@link com.sks.script.ScriptMapper} 同模式）。
 *
 * <p><b>upsert</b>（{@link #upsert}）：靠 {@code weekly_report} 的 UNIQUE(user_id, week_start) 约束作 backstop——
 * {@code ON CONFLICT (user_id, week_start) DO UPDATE SET content = EXCLUDED.content}。重跑同周覆盖 content，
 * 不重复插行（{@link WeeklyReportJob} 幂等）。
 */
@Mapper
public interface WeeklyReportMapper extends BaseMapper<WeeklyReport> {

    /**
     * upsert 周归因报告。{@code contentJson} 必须是合法 JSON 文本（由调用方组装）。
     * UNIQUE(user_id, week_start) 冲突 → 覆盖 content（幂等，重跑同周不重复插行）。
     */
    @Insert(
            "INSERT INTO weekly_report(user_id, week_start, content) "
                    + "VALUES(#{userId}, #{weekStart}, #{content}::jsonb) "
                    + "ON CONFLICT (user_id, week_start) DO UPDATE SET content = EXCLUDED.content")
    int upsert(
            @Param("userId") long userId,
            @Param("weekStart") LocalDate weekStart,
            @Param("content") String contentJson);

    /**
     * 取某用户某周的报告 content（IDOR-scoped：带 user_id 过滤，§5.1）。无 → null。
     *
     * <p>供 {@link WeeklyReportController} 的 {@code GET /api/review/weekly} 端点返回（content 为 JSONB 文本，
     * 由前端 parse）。
     */
    @Select(
            "SELECT content FROM weekly_report WHERE user_id = #{userId} AND week_start = #{weekStart}")
    String findContentByUserAndWeek(
            @Param("userId") long userId, @Param("weekStart") LocalDate weekStart);
}
