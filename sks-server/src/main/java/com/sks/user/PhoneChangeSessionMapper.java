package com.sks.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/** phone_change_session 的 Mapper。 */
@Mapper
public interface PhoneChangeSessionMapper extends BaseMapper<PhoneChangeSession> {

    @Select("SELECT * FROM phone_change_session WHERE token = #{token}")
    PhoneChangeSession findByToken(String token);

    /** send-old-code 重入时先删未完成 session（不标 DONE，避免污染语义）。 */
    @Delete("DELETE FROM phone_change_session WHERE user_id = #{userId} AND status <> 'DONE'")
    int deleteActiveByUserId(Long userId);

    /** verify-old 对码：置 AWAITING_NEW_VERIFY + old_verified_at + 重置 expires_at。 */
    @Update("UPDATE phone_change_session SET status='AWAITING_NEW_VERIFY', "
            + "old_verified_at=#{oldVerifiedAt}, expires_at=#{expiresAt} WHERE id=#{id}")
    int updateToNewVerify(@Param("id") Long id,
                           @Param("oldVerifiedAt") OffsetDateTime oldVerifiedAt,
                           @Param("expiresAt") OffsetDateTime expiresAt);

    /** send-new-code：落 new_phone。 */
    @Update("UPDATE phone_change_session SET new_phone=#{newPhone} WHERE id=#{id}")
    int updateNewPhone(@Param("id") Long id, @Param("newPhone") String newPhone);

    /** verify-new 成功：置 DONE。 */
    @Update("UPDATE phone_change_session SET status='DONE' WHERE id=#{id}")
    int markDone(Long id);
}
