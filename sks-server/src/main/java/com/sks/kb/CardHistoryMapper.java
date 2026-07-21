package com.sks.kb;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 卡片历史归档 Mapper。
 *
 * <p>{@code old_content} 为 JSONB，用 {@code #{oldContent}::jsonb} 显式 cast（{@link BaseMapper#insert}
 * 自动生成的 SQL 不带 cast，varchar→jsonb 会报 PG 类型错）。
 *
 * <p>{@code @Mapper} 让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface CardHistoryMapper extends BaseMapper<CardHistory> {

    /** 归档旧 content：B 层卡片编辑时，把更新前的 content 存到这里。 */
    @Insert(
            "INSERT INTO card_history(card_id, old_content) VALUES(#{cardId}, #{oldContent}::jsonb)")
    int insertHistory(@Param("cardId") long cardId, @Param("oldContent") String oldContent);

    /** 某卡的归档条数（测试断言「旧值已归档」用）。 */
    @Select("SELECT COUNT(*) FROM card_history WHERE card_id = #{cardId}")
    int countByCard(@Param("cardId") long cardId);
}
