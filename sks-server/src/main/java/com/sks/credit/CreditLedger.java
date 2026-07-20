package com.sks.credit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 额度流水实体（表 {@code credit_ledger}）。
 *
 * <p>{@code delta}：带符号——充/退为 {@code +n}，扣减为 {@code -n}。{@code type}：{@code debit/credit/refund}。
 * 幂等键为唯一约束 {@code UNIQUE(biz_id, biz_type, type)}——同一业务键下，debit/credit/refund 各至多一条。
 * 表无 {@code deleted} 列，全局逻辑删除配置对本实体不生效。
 */
@TableName("credit_ledger")
public class CreditLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer delta;

    private String bizType;

    private String bizId;

    private String type;

    private String memo;

    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
