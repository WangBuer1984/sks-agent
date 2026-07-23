package com.sks.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 换绑手机号 2-step flow 的 session 状态（表 {@code phone_change_session}）。 */
@TableName("phone_change_session")
public class PhoneChangeSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String token;
    private Long userId;
    private String oldPhone;
    private String newPhone;
    private String status;
    private OffsetDateTime oldVerifiedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOldPhone() { return oldPhone; }
    public void setOldPhone(String oldPhone) { this.oldPhone = oldPhone; }
    public String getNewPhone() { return newPhone; }
    public void setNewPhone(String newPhone) { this.newPhone = newPhone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getOldVerifiedAt() { return oldVerifiedAt; }
    public void setOldVerifiedAt(OffsetDateTime oldVerifiedAt) { this.oldVerifiedAt = oldVerifiedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
