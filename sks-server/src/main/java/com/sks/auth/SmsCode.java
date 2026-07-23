package com.sks.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 短信验证码实体（表 {@code sms_code}）。
 *
 * <p>{@code err_count} 复用为 5 次错误锁定标记——当最近一条记录 {@code err_count >= 5} 且 {@code created_at}
 * 距今不足 10 分钟即视为锁定（无需额外列）。{@code used=true} 表示该码已被成功登录消费。
 */
@TableName("sms_code")
public class SmsCode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String code;
    private OffsetDateTime expireAt;
    private Integer errCount;
    private Boolean used;
    private String scene;
    private String sessionToken;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public OffsetDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(OffsetDateTime expireAt) { this.expireAt = expireAt; }

    public Integer getErrCount() { return errCount; }
    public void setErrCount(Integer errCount) { this.errCount = errCount; }

    public Boolean getUsed() { return used; }
    public void setUsed(Boolean used) { this.used = used; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
