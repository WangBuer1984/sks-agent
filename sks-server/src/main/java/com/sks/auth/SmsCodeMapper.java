package com.sks.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * sms_code 的 Mapper：三级频控的滚动窗口聚合、最近码查询、err_count 自增、used 标记。
 *
 * <p>所有频控查询都用 {@code created_at >= now() - interval 'X'} 的滚动窗口（而非自然日重置），
 * 走 {@code idx_sms_phone_time} 索引——与 PRD §11.1 滑动窗口语义一致，避免 00:00 提前解锁。
 */
@Mapper
public interface SmsCodeMapper extends BaseMapper<SmsCode> {

    /** 最近 1 分钟内该手机号发出的码数（频控：≥1 即拒）。 */
    @Select(
            "SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
                    + "AND created_at >= now() - interval '1 minute'")
    long countLastMinute(String phone);

    /** 最近 1 小时内该手机号发出的码数（频控：≥5 即拒）。 */
    @Select(
            "SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
                    + "AND created_at >= now() - interval '1 hour'")
    long countLastHour(String phone);

    /** 最近 24 小时内该手机号发出的码数（频控：≥10 即拒，替代 PRD 的「超日限锁 24h」）。 */
    @Select(
            "SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
                    + "AND created_at >= now() - interval '24 hours'")
    long countLast24Hours(String phone);

    /** 该手机号最近一条验证码（不论 used/过期，用于锁定判定）。 */
    @Select("SELECT * FROM sms_code WHERE phone = #{phone} ORDER BY created_at DESC LIMIT 1")
    SmsCode findMostRecent(String phone);

    /** 该手机号最近一条未使用且未过期的码（用于 login 校验）。 */
    @Select(
            "SELECT * FROM sms_code WHERE phone = #{phone} AND used = false AND expire_at > now() "
                    + "ORDER BY created_at DESC LIMIT 1")
    SmsCode findActiveCode(String phone);

    /** 错误次数 +1（命中错误码或锁定进入时调用）。 */
    @Update("UPDATE sms_code SET err_count = err_count + 1 WHERE id = #{id}")
    int incrementErrCount(Long id);

    /** 标记该码已被成功登录消费。 */
    @Update("UPDATE sms_code SET used = true WHERE id = #{id}")
    int markUsed(Long id);
}
