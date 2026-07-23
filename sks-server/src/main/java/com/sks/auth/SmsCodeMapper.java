package com.sks.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * sms_code 的 Mapper：三级频控滚动窗口聚合、最近码查询、err_count 自增、used 标记、scene 化比对、锁定判定。
 *
 * <p>频控查询保持按 phone 全局（不加 scene）；比对查询按 (phone, scene) 防跨 scene 码互用；
 * 锁定判定 existsLocked 取 ANY-row（任一 scene 锁 5 次则全号锁，防稀释绕过）。spec §3.0。
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

    /** 该手机号最近一条码（不论 used/过期/scene，用于测试 helper 与历史排查）。 */
    @Select("SELECT * FROM sms_code WHERE phone = #{phone} ORDER BY created_at DESC LIMIT 1")
    SmsCode findMostRecent(String phone);

    /** 该手机号 + scene 最近一条未使用未过期码（防跨 scene 互用：登录码不能过 verify-old）。 */
    @Select(
            "SELECT * FROM sms_code WHERE phone = #{phone} AND scene = #{scene} "
                    + "AND used = false AND expire_at > now() ORDER BY created_at DESC LIMIT 1")
    SmsCode findActiveCode(@Param("phone") String phone, @Param("scene") String scene);

    /** 锁定判定（ANY-row）：任一 scene 的码 err_count>=5 且 10 分钟内 → 全号锁。 */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM sms_code WHERE phone = #{phone} "
                    + "AND err_count >= 5 AND created_at > now() - interval '10 minute')")
    boolean existsLocked(String phone);

    /** 错误次数 +1（命中错误码或锁定进入时调用）。 */
    @Update("UPDATE sms_code SET err_count = err_count + 1 WHERE id = #{id}")
    int incrementErrCount(Long id);

    /** 标记该码已被成功登录消费。 */
    @Update("UPDATE sms_code SET used = true WHERE id = #{id}")
    int markUsed(Long id);

    /** verify-new 成功：作废本次换绑的码（token 关联）。 */
    @Update("UPDATE sms_code SET used = true WHERE session_token = #{token} AND used = false")
    int invalidateByToken(String token);

    /** verify-new 成功：作废 old/new 号的 pending 码（同用户清理，防重放）。 */
    @Update(
            "UPDATE sms_code SET used = true WHERE phone IN (#{oldPhone},#{newPhone}) AND used = false")
    int invalidateByPhones(@Param("oldPhone") String oldPhone, @Param("newPhone") String newPhone);
}
