package com.sks.analyze;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 拆解任务 Mapper（表 {@code analyze_task}，Flyway V1 已建）。
 *
 * <p><b>自定义 SQL</b>（不用 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} 自动方法）——
 * 与 {@code script} / {@code kb_card} 同模式：{@code input} / {@code result} 是 JSONB 列，
 * BaseMapper 自动拼的 INSERT/UPDATE 不带 {@code ::jsonb} cast，PG 报类型不匹配。显式
 * {@code #{...}::jsonb} cast。analyze_task 无向量列，比 kb_card 简单（无 VectorTypeHandler）。
 *
 * <p><b>无逻辑删除</b>：{@code analyze_task} 无 {@code deleted} 列，终态行保留供对账 / 历史查询。
 */
@Mapper
public interface AnalyzeTaskMapper {

    /**
     * 插占位行（{@code status='queued', progress=0}）拿稳定 {@code taskId}——§4.3 扣费前先建占位行
     * 作退款幂等键（{@code (biz_id=taskId, biz_type, type='refund')} 唯一约束）。
     * {@code input} 为请求参数 JSONB（视频 text 模式存 transcript，account 模式存 url）。
     * {@code @Options(useGeneratedKeys=true)} 回填 id。
     */
    @Insert(
            "INSERT INTO analyze_task(user_id, task_type, status, progress, charged, input) "
                    + "VALUES(#{userId}, #{taskType}, 'queued', 0, #{charged}, #{input}::jsonb)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalyzeTask task);

    /**
     * video/text 同步成功路径回填：{@code status='done', progress=100, result} + updated_at。
     * Python 内部已写一次 done+result，Java 重写幂等（防 Python 写后 Java 读 HTTP 失败的中间态）。
     */
    @Update(
            "UPDATE analyze_task SET status = 'done', progress = 100, result = #{result}::jsonb, "
                    + "error = NULL, updated_at = now() WHERE id = #{id}")
    int markDone(@Param("id") long id, @Param("result") String result);

    /** 失败路径：{@code status='failed'} + 可选 error + updated_at。退款前 / 后调（顺序见 AnalyzeService）。 */
    @Update(
            "UPDATE analyze_task SET status = 'failed', error = #{error}, updated_at = now() WHERE id = #{id}")
    int markFailed(@Param("id") long id, @Param("error") String error);

    /** 测试辅助 + Python partial 写入后 Java 读：置 {@code status='partial', progress, updated_at}。 */
    @Update(
            "UPDATE analyze_task SET status = 'partial', progress = #{progress}, updated_at = now() WHERE id = #{id}")
    int markPartial(@Param("id") long id, @Param("progress") int progress);

    /** 轮询器：running 超时（updated_at 早于 cutoff）的任务列表——判 failed 全额退。 */
    @Select(
            "SELECT * FROM analyze_task WHERE status = 'running' AND updated_at < #{cutoff} ORDER BY id")
    List<AnalyzeTask> findStaleRunning(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /** 轮询器：partial（终态，按比例退一次）的任务列表。含已退过的——refund 幂等挡住双退。 */
    @Select("SELECT * FROM analyze_task WHERE status = 'partial' ORDER BY id")
    List<AnalyzeTask> findPartial();

    /** 轮询器：stale-queued（updated_at 早于 cutoff，Python 返回 202 后即崩）→ 判 failed 全额退。 */
    @Select(
            "SELECT * FROM analyze_task WHERE status = 'queued' AND updated_at < #{cutoff} ORDER BY id")
    List<AnalyzeTask> findStaleQueued(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /** 轮询器：account 任务 done 后写 benchmark 选题（终态，幂等）。 */
    @Select(
            "SELECT * FROM analyze_task WHERE status = 'done' AND task_type = 'account' ORDER BY id")
    List<AnalyzeTask> findDoneAccount();

    /** 取任务（含 result JSONB 文本）。带 user_id 过滤（IDOR 防护，§5.1）——跨用户返回 null。 */
    @Select("SELECT * FROM analyze_task WHERE id = #{id} AND user_id = #{userId}")
    AnalyzeTask findById(@Param("id") long id, @Param("userId") long userId);
}
