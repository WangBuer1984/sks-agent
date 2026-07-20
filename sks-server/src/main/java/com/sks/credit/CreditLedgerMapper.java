package com.sks.credit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 额度流水 Mapper。
 *
 * <p>{@link #countByBizKey} 是幂等性「先查」守卫——按 {@code (biz_id, biz_type, type)} 查是否已入账，
     * 已存在则直接 return，不触碰余额。唯一约束 {@code UNIQUE(biz_id, biz_type, type)} 作为并发重复兜底。
 *
 * <p>{@code @Mapper} 让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface CreditLedgerMapper extends BaseMapper<CreditLedger> {

    /** 按 幂等键 {@code (biz_id, biz_type, type)} 查询已存在的流水数（>0 表示已应用过）。 */
    @Select(
            "SELECT COUNT(*) FROM credit_ledger "
                    + "WHERE biz_id = #{bizId} AND biz_type = #{bizType} AND type = #{type}")
    int countByBizKey(
            @Param("bizId") String bizId,
            @Param("bizType") String bizType,
            @Param("type") String type);
}
