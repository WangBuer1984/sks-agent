package com.sks.credit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 额度账户 Mapper。
 *
 * <p>{@link #deduct} 是「原子条件扣减」——单条 {@code UPDATE} 同时完成「余额够不够」判定与扣减，
 * 靠影响行数判断成败，绝不 read-then-write。这是项目 #1 资金不变量。
 *
 * <p>{@code @Mapper} 让主类的 {@code @MapperScan(annotationClass=Mapper.class)} 扫描到本接口。
 */
@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    /**
     * 原子条件扣减：{@code balance >= n} 才扣，返回影响行数（1=成功，0=余额不足）。
     *
     * <p>单语句原子，PG 行锁覆盖「检查 + 扣减」，并发下不会超扣。
     */
    @Update(
            "UPDATE credit_account SET balance = balance - #{n}, updated_at = now() "
                    + "WHERE user_id = #{uid} AND balance >= #{n}")
    int deduct(@Param("uid") long userId, @Param("n") int n);

    /** 加回额度（退款 / 充值入账用）：无条件 +n。调用方保证已在事务内、且已通过幂等校验。 */
    @Update(
            "UPDATE credit_account SET balance = balance + #{n}, updated_at = now() "
                    + "WHERE user_id = #{uid}")
    int addBalance(@Param("uid") long userId, @Param("n") int n);

    /** 读取余额；无行返回 null（由 Service 翻译为 0）。 */
    @Select("SELECT balance FROM credit_account WHERE user_id = #{uid}")
    Integer selectBalance(@Param("uid") long userId);

    /**
     * 幂等建账：账户不存在则插入 {@code balance=0} 一行，已存在则 no-op。
     * 注册钩子（Task 0.7）与 {@code credit} 首次入账前调用，可重复调用。
     */
    @Insert(
            "INSERT INTO credit_account(user_id, balance) VALUES(#{uid}, 0) ON CONFLICT DO NOTHING")
    int ensureAccount(@Param("uid") long userId);
}
