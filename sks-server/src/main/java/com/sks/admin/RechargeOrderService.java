package com.sks.admin;

import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.credit.CreditService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 充值 / 开通 / 补偿订单服务——管理端人工开通与补偿额度的业务核心，与 {@link CreditService}（钱核心）
 * 在同一事务内协作。
 *
 * <p>三条主线（tech-design §4.1 资金链路、Task 0.7 brief）：
 *
 * <ol>
 *   <li><b>注册钩子</b>（{@link #onUserRegistered}）：{@link com.sks.auth.AuthService#login} 首次注册
 *       新用户时调用。三步原子：① {@link CreditService#ensureAccount} 建账 → ②
 *       {@link #createTrialOrder} 建 {@code status='trial'} 的免费体验单 → ③
 *       {@link CreditService#credit} 送 {@code sks.trial-credit}（默认 3）条体验额度
 *       （{@code biz_type='trial'}, {@code biz_id=trial订单id}）。免费体验必须真能体验——开通前用户可
 *       实际生成几条感受价值。
 *   <li><b>开通</b>（{@link #open}）：管理端「开通」按钮。trial 单存在 → 首次开通：转 trial→done +
 *       回填 pkg/amount/admin_user_id/opened_at/is_first_charge + credit 套餐 + （首充时）credit
 *       bonus 10；trial 单不存在 → 复购：新建 done 单 + credit 套餐（无 bonus）。
 *   <li><b>补偿</b>（{@link #compensate}）：建 {@code order_type='compensate'} 的 done 单留痕 +
 *       credit 补偿额度。补偿单 <b>不参与首充判定</b>（{@code order_type} 不同，天然排除）。
 * </ol>
 *
 * <p><b>首充口径（唯一）</b>（{@link RechargeOrderMapper#countPriorDoneRecharge}）：本单转为 done 之前，
 * 用户名下 {@code order_type='recharge' AND status='done'} 的单数。0 → 首充 → bonus 10 +
 * {@code is_first_charge=true}；&gt;0 → 非首充 → 无 bonus。补偿单 {@code order_type='compensate'} 不计入。
 *
 * <p><b>幂等模型</b>：开通的套餐额度 {@code credit(biz_type='recharge', biz_id=orderId)} 与首充赠送
 * {@code credit(biz_type='bonus', biz_id=orderId)} 用同一 orderId 但 biz_type 不同，{@link CreditService}
 * 的唯一约束 {@code UNIQUE(biz_id, biz_type, type)} 不冲突——同 orderId 重复调用静默 no-op（管理员重试
 * 同一单不会重复入账，是第二道防线）。
 *
 * <p><b>复购双击已知局限</b>：trial→done 后再次调用 {@link #open} 会走复购分支建新 done 单——新订单 id
 * 不同，{@code credit} 的 {@code (biz_id, biz_type, type)} 幂等不拦（不同 biz_id）。管理员对复购双击会
 * 重复入账。MVP 不引入 client-idempotency-key（YAGNI），UI 层防双击 + 后续按需补强。
 *
 * <p><b>SMS 留桩</b>：开通/补偿「发短信」仅打日志，不调真实网关（联调时替换）。
 */
@Service
public class RechargeOrderService {

    private static final Logger log = LoggerFactory.getLogger(RechargeOrderService.class);

    /** p50 套餐：50 条，金额 49。 */
    private static final String PKG_P50 = "p50";
    /** p150 套餐：150 条，金额 129。 */
    private static final String PKG_P150 = "p150";
    /** 首充赠送额度。 */
    private static final int FIRST_CHARGE_BONUS = 10;

    private final RechargeOrderMapper rechargeOrderMapper;
    private final CreditService creditService;
    private final AppUserMapper appUserMapper;
    private final int trialCredit;

    public RechargeOrderService(
            RechargeOrderMapper rechargeOrderMapper,
            CreditService creditService,
            AppUserMapper appUserMapper,
            @Value("${sks.trial-credit:3}") int trialCredit) {
        this.rechargeOrderMapper = rechargeOrderMapper;
        this.creditService = creditService;
        this.appUserMapper = appUserMapper;
        this.trialCredit = trialCredit;
    }

    /**
     * 注册钩子：{@link com.sks.auth.AuthService#login} 首次注册新用户时调用。三步原子（同一
     * {@code @Transactional}）：① {@link CreditService#ensureAccount} → ② {@link #createTrialOrder} →
     * ③ {@link CreditService#credit} 送体验额度。
     *
     * <p>独立事务方法（不被 login 的非事务边界吞没）：login 自身非 {@code @Transactional}，本方法自给自足
     * 一个事务，3 步要么全成要么全滚。login 的 app_user 插入已先 auto-commit，本方法失败回滚不影响
     * app_user 落库——用户可重新发码登录触发重试（{@link CreditService#credit} 幂等，重试不重复入账）。
     */
    @Transactional
    public void onUserRegistered(long userId) {
        creditService.ensureAccount(userId);
        long trialOrderId = createTrialOrder(userId);
        creditService.credit(userId, trialCredit, "trial", String.valueOf(trialOrderId), "注册体验");
    }

    /**
     * 建免费体验单：{@code order_type='recharge', status='trial', pkg=null, amount=0}。返回新单 id
     * （注册钩子用作 credit 的 biz_id）。可独立调用供测试断言。
     */
    public long createTrialOrder(long userId) {
        RechargeOrder o = new RechargeOrder();
        o.setUserId(userId);
        o.setOrderType("recharge");
        o.setStatus("trial");
        // pkg=null, amount=0, is_first_charge=false, admin_user_id=null, opened_at=null 走 DB 默认值
        rechargeOrderMapper.insert(o);
        return o.getId();
    }

    /**
     * 开通：trial 单存在 → 首次开通（trial→done + 套餐 + 可能的首充 bonus）；trial 单不存在 → 复购
     * （新建 done 单 + 套餐，无 bonus）。返回更新后余额。
     *
     * <p>全程同一 {@code @Transactional}：纯 DB（建/改单 + 1-2 次 credit），无外部调用，credit 内部
     * {@code @Transactional}(REQUIRED) 加入本事务。
     *
     * @param userId 目标用户 id
     * @param pkg 套餐：{@code p50}（50 条 / ¥49）或 {@code p150}（150 条 / ¥129）
     * @param adminUserId 操作管理员 id（来自 admin JWT principal）
     * @return 开通后用户余额
     */
    @Transactional
    public int open(long userId, String pkg, long adminUserId) {
        int[] pa = pkgToAmount(pkg);
        int creditN = pa[0];
        int amount = pa[1];

        RechargeOrder trial = rechargeOrderMapper.findTrialOrder(userId);
        long orderId;
        boolean isFirst;
        if (trial != null) {
            // 首次开通：trial→done 转换。首充口径 = 转换前 done recharge 单数（trial 尚未 done，不计入）。
            isFirst = rechargeOrderMapper.countPriorDoneRecharge(userId) == 0;
            rechargeOrderMapper.convertToDone(
                    trial.getId(), pkg, amount, adminUserId, isFirst);
            orderId = trial.getId();
        } else {
            // 复购：直接建 done 单。已有 done recharge 单 → 非首充。
            isFirst = false;
            RechargeOrder o = new RechargeOrder();
            o.setUserId(userId);
            o.setOrderType("recharge");
            o.setPkg(pkg);
            o.setAmount(amount);
            o.setStatus("done");
            o.setIsFirstCharge(false);
            o.setAdminUserId(adminUserId);
            o.setOpenedAt(java.time.OffsetDateTime.now());
            rechargeOrderMapper.insert(o);
            orderId = o.getId();
        }

        // 套餐额度入账（biz_type=recharge）。CreditService 幂等：同 orderId 重复 credit 静默 no-op。
        creditService.credit(userId, creditN, "recharge", String.valueOf(orderId), "套餐开通 " + pkg);
        if (isFirst) {
            // 首充赠送 10 条（biz_type=bonus，同 orderId 但 biz_type 不同，不撞唯一约束）。
            creditService.credit(
                    userId, FIRST_CHARGE_BONUS, "bonus", String.valueOf(orderId), "首充赠送");
        }

        // SMS 留桩：MVP 不调真实网关，联调时替换。
        log.info(
                "[SMS-STUB] open user={} pkg={} amount={} orderId={} isFirst={} admin={}",
                userId,
                pkg,
                amount,
                orderId,
                isFirst,
                adminUserId);

        return creditService.balance(userId);
    }

    /**
     * 补偿额度：建 {@code order_type='compensate', status='done', pkg='补偿+N', amount=0} 单留痕 →
     * {@link CreditService#credit}（{@code biz_type='compensate'}）。返回更新后余额。
     *
     * <p>补偿单 {@code order_type='compensate'} <b>不参与首充判定</b>——首充口径 SQL 只统计
     * {@code order_type='recharge' AND status='done'} 的单。补偿后再 {@link #open} 仍享首充 bonus。
     */
    @Transactional
    public int compensate(long userId, int n, String memo, long adminUserId) {
        RechargeOrder o = new RechargeOrder();
        o.setUserId(userId);
        o.setOrderType("compensate");
        o.setPkg("补偿+" + n);
        o.setAmount(0);
        o.setStatus("done");
        o.setIsFirstCharge(false);
        o.setAdminUserId(adminUserId);
        o.setOpenedAt(java.time.OffsetDateTime.now());
        o.setMemo(memo);
        rechargeOrderMapper.insert(o);

        creditService.credit(userId, n, "compensate", String.valueOf(o.getId()), memo);

        log.info(
                "[SMS-STUB] compensate user={} n={} orderId={} admin={} memo={}",
                userId,
                n,
                o.getId(),
                adminUserId,
                memo);

        return creditService.balance(userId);
    }

    /** 用户最近一条订单（任意 type/status），用于断言开通/补偿后的最新状态。 */
    public RechargeOrder latestOrder(long userId) {
        return rechargeOrderMapper.findLatest(userId);
    }

    /**
     * 管理端按手机尾号搜用户：返回 {@code [{userId, phoneMasked, balance, latestOrderStatus}]}。
     * {@code phoneMasked} = 保留前 3 后 4，中间用 {@code ****} 遮蔽（如 {@code 138****0001}）。
     */
    public List<Map<String, Object>> searchUsersByPhoneTail(String phoneTail) {
        List<AppUser> users = appUserMapper.findByPhoneTail(phoneTail);
        List<Map<String, Object>> out = new ArrayList<>(users.size());
        for (AppUser u : users) {
            RechargeOrder latest = rechargeOrderMapper.findLatest(u.getId());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("userId", u.getId());
            row.put("phoneMasked", maskPhone(u.getPhone()));
            row.put("balance", creditService.balance(u.getId()));
            row.put("latestOrderStatus", latest == null ? null : latest.getStatus());
            out.add(row);
        }
        return out;
    }

    /**
     * 管理端订单列表：返回 {@code [{orderId, userId, phoneTail, pkg, status, adminUserId, ...}]}。
     * user 手机尾号在 service 层按 user_id 批量 join（避免 mapper 跨表 ResultMap 复杂度）。
     */
    public List<Map<String, Object>> listOrders(String status) {
        List<RechargeOrder> orders = rechargeOrderMapper.listOrders(status);
        List<Map<String, Object>> out = new ArrayList<>(orders.size());
        for (RechargeOrder o : orders) {
            AppUser u = appUserMapper.selectById(o.getUserId());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("orderId", o.getId());
            row.put("userId", o.getUserId());
            row.put("phoneTail", u == null ? null : phoneTail(u.getPhone()));
            row.put("orderType", o.getOrderType());
            row.put("pkg", o.getPkg());
            row.put("amount", o.getAmount());
            row.put("status", o.getStatus());
            row.put("isFirstCharge", o.getIsFirstCharge());
            row.put("adminUserId", o.getAdminUserId());
            row.put("memo", o.getMemo());
            row.put("openedAt", o.getOpenedAt());
            row.put("createdAt", o.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** 套餐 → {creditN, amount}。未知 pkg 抛 {@link BizException}。 */
    private int[] pkgToAmount(String pkg) {
        return switch (pkg) {
            case PKG_P50 -> new int[] {50, 49};
            case PKG_P150 -> new int[] {150, 129};
            default -> throw new BizException(ErrorCode.PARAM_INVALID);
        };
    }

    /** 手机号遮蔽：11 位 → 前 3 + {@code ****} + 后 4；非 11 位 → 保留前 3 后 2，中间遮蔽。 */
    static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        if (phone.length() <= 6) {
            return phone;
        }
        int head = Math.min(3, phone.length() - 4);
        return phone.substring(0, head) + "****" + phone.substring(phone.length() - 4);
    }

    /** 取手机尾号（后 4 位），用于订单列表展示。 */
    private static String phoneTail(String phone) {
        if (phone == null || phone.length() < 4) return phone;
        return phone.substring(phone.length() - 4);
    }
}
