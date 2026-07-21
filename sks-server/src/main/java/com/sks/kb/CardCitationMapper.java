package com.sks.kb;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 卡片引用 Mapper。
 *
 * <p>删除守卫用 {@link #countByCard}：引用数 > 0 且非 force 时抛
 * {@link com.sks.common.ErrorCode#CARD_IN_USE}。引用不软删（无 {@code deleted} 列），
 * 卡片软删后引用行仍在（历史溯源）。
 *
 * <p>{@code @Mapper} 让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 * {@link BaseMapper#insert} 可直接用于本实体（无非标准类型列，纯 bigint + timestamptz 默认值）。
 */
@Mapper
public interface CardCitationMapper extends BaseMapper<CardCitation> {

    /** 某卡的引用数（删除守卫）。引用不软删，直接 count 全量。 */
    @Select("SELECT COUNT(*) FROM card_citation WHERE card_id = #{cardId}")
    int countByCard(@Param("cardId") long cardId);
}
