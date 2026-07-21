package com.sks.analyze;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 拆账号 TOP20 明细 Mapper（表 {@code benchmark_video}，Flyway V1 已建）。
 *
 * <p>Python 直接 INSERT 此表（{@code analyze_store.insert_benchmark_video}，Task 3.2）——Java 侧
 * MVP 仅读（展示 TOP20 清单）。{@code structure} 为 JSONB 列，读时 PG 自动以文本返回
 * （{@code SELECT *} 由 MyBatis map-underscore-to-camel-case 映射）。V1.1 深拆 / 仿写再补写方法。
 */
@Mapper
public interface BenchmarkVideoMapper {

    /** 列出某拆账号任务的 TOP20 明细（按 created_at 升序，保持 Python 写入顺序）。 */
    @Select(
            "SELECT id, analyze_task_id, title, play_count, fav_count, transcript, structure, created_at "
                    + "FROM benchmark_video WHERE analyze_task_id = #{taskId} ORDER BY created_at, id")
    List<BenchmarkVideo> listByTask(@Param("taskId") long taskId);
}
