package com.sks.admin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * recharge_order 的 Mapper：BaseMapper 提供通用 CRUD，自定义方法覆盖 trial 单查找、首充口径计数、
 * trial→done 转换、用户尾号搜索、订单列表。
 *
 * <p>{@code @Mapper} 注解让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 *
 * <p><b>首充口径 SQL</b>（{@link #countPriorDoneRecharge}）：仅统计 {@code order_type='recharge' AND
 * status='done'} 的单——补偿单（{@code order_type='compensate'}）天然排除，不参与首充判定。
 */
@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /**
     * 找用户的 trial 体验单（{@code status='trial' AND order_type='recharge'}）。首充开通时若查到 →
     * 把它转 done；查不到 → 复购（建新 done 单）。
     */
    @Select(
            "SELECT * FROM recharge_order WHERE user_id = #{uid} AND order_type = 'recharge' "
                    + "AND status = 'trial' ORDER BY id LIMIT 1")
    RechargeOrder findTrialOrder(@Param("uid") long userId);

    /**
     * 首充口径：用户名下 {@code order_type='recharge' AND status='done'} 的单数。<b>不</b>统计
     * {@code order_type='compensate'} 的补偿单。本单（trial 单）尚未转 done，故不计入。
     */
    @Select(
            "SELECT COUNT(*) FROM recharge_order WHERE user_id = #{uid} "
                    + "AND order_type = 'recharge' AND status = 'done'")
    int countPriorDoneRecharge(@Param("uid") long userId);

    /**
     * trial→done 转换：回填 pkg/amount/admin_user_id/opened_at/is_first_charge，置 status='done'。
     * 调用方保证本单当前为 trial 状态。
     */
    @Update(
            "UPDATE recharge_order SET status='done', pkg=#{pkg}, amount=#{amount}, "
                    + "admin_user_id=#{adminUserId}, opened_at=now(), is_first_charge=#{isFirst} "
                    + "WHERE id = #{orderId}")
    int convertToDone(
            @Param("orderId") long orderId,
            @Param("pkg") String pkg,
            @Param("amount") int amount,
            @Param("adminUserId") long adminUserId,
            @Param("isFirst") boolean isFirst);

    /** 用户最近一条订单（任意 type/status，按创建时间倒序），用于断言开通/补偿后的最新状态。 */
    @Select("SELECT * FROM recharge_order WHERE user_id = #{uid} ORDER BY created_at DESC, id DESC LIMIT 1")
    RechargeOrder findLatest(@Param("uid") long userId);

    /** 订单列表，按 status 过滤（null → 全部），按创建时间倒序。user 手机尾号在 service 层 join。
     *  {@code #{status}::text} 必须显式转型：PG 对 {@code #{status} IS NULL} 中的 null 参数无法推断类型，
     *  JDBC 发出 unspecified-type null → 报 {@code could not determine data type of parameter $1}。
     *  加 {@code ::text} 后参数为 typed null，null/非 null 两路径都通。 */
    @Select(
            "SELECT * FROM recharge_order "
                    + "WHERE (#{status}::text IS NULL OR status = #{status}) "
                    + "ORDER BY created_at DESC, id DESC")
    List<RechargeOrder> listOrders(@Param("status") String status);
}
