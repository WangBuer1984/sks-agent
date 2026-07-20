package com.sks.admin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 充值 / 补偿订单实体（表 {@code recharge_order}）。
 *
 * <p>同一张表承载两类单：
 *
 * <ul>
 *   <li><b>recharge</b>（{@code order_type='recharge'}）：注册时自动建 {@code status='trial'} 的免费体验单，
 *       管理端「开通」把 trial 单转 {@code status='done'} 并回填 pkg/amount/admin_user_id/opened_at；
 *       复购时直接建 {@code status='done'} 的新单。
 *   <li><b>compensate</b>（{@code order_type='compensate'}）：补偿单，{@code status='done'}，
 *       {@code pkg='补偿+N'}，{@code amount=0}，不参与首充判定。
 * </ul>
 *
 * <p><b>首充口径</b>（{@code is_first_charge}）：该单是否为用户的「首次开通」——判定依据是
 * {@code status='done' AND order_type='recharge'} 的单在本单之前是否已存在 0 条。补偿单
 * {@code order_type='compensate'} 天然不算。
 *
 * <p>列名下划线风格由 MyBatis-Plus 的 {@code map-underscore-to-camel-case} 自动映射。表无 {@code deleted}
 * 列，全局逻辑删除配置对本实体不生效。
 */
@TableName("recharge_order")
public class RechargeOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String orderType;
    private String pkg;
    private Integer amount;
    private String phoneTail;
    private String status;
    private Boolean isFirstCharge;
    private Long adminUserId;
    private OffsetDateTime openedAt;
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

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public String getPkg() {
        return pkg;
    }

    public void setPkg(String pkg) {
        this.pkg = pkg;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public String getPhoneTail() {
        return phoneTail;
    }

    public void setPhoneTail(String phoneTail) {
        this.phoneTail = phoneTail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsFirstCharge() {
        return isFirstCharge;
    }

    public void setIsFirstCharge(Boolean isFirstCharge) {
        this.isFirstCharge = isFirstCharge;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(Long adminUserId) {
        this.adminUserId = adminUserId;
    }

    public OffsetDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(OffsetDateTime openedAt) {
        this.openedAt = openedAt;
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
