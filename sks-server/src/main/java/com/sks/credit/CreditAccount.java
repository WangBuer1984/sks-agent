package com.sks.credit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 额度账户实体（表 {@code credit_account}）。
 *
 * <p>主键是 {@code user_id}（外键到 {@code app_user.id}，由调用方提供），故 {@link IdType#INPUT}。
 * 表无 {@code deleted} 列，MyBatis-Plus 全局逻辑删除配置对本实体不生效（与 {@code app_user} 一致）。
 */
@TableName("credit_account")
public class CreditAccount {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private Integer balance;

    private OffsetDateTime updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(Integer balance) {
        this.balance = balance;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
