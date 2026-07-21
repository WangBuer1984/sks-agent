package com.sks.auth;

import com.sks.admin.RechargeOrderService;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.JwtUtil;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端登录核心服务：手机号验证码的下发、三级频控、5 次错误锁定、登录/注册一体。
 *
 * <p>关键设计（与 PRD §11.1、tech-design §4.2 一致）：
 *
 * <ul>
 *   <li><b>三级频控</b>：同手机号 1 分钟 ≤1 条、1 小时 ≤5 条、24 小时 ≤10 条；超限抛 {@link
 *       ErrorCode#SMS_RATE_LIMIT}。三级窗口统一用 {@code created_at} 滚动聚合（走 {@code
 *       idx_sms_phone_time}），替代 PRD 的「超日限锁 24h」字段——无需额外锁定列，且避免 00:00 提前解锁。
 *   <li><b>5 次错误锁定 10 分钟</b>：复用 {@code sms_code.err_count}——最近一条码 {@code err_count >= 5}
 *       且 {@code created_at} 距今不足 10 分钟即视为锁定。锁定期间既不可登录也不可发新码，抛 {@link
 *       ErrorCode#SMS_CODE_LOCKED}。验证码本身 5 分钟过期，锁定窗口比它长，复用已有列无需新列。
 *   <li><b>登录即注册</b>：手机号不存在则插入 {@code app_user}，返回 {@code isNew=true}。首次注册
 *       （{@code isNew=true}）时调用 {@link RechargeOrderService#onUserRegistered} 注册钩子——三步原子
 *       （① {@code ensureAccount} 建账 → ② 建 {@code status='trial'} 免费体验单 → ③ {@code credit} 送
 *       {@code sks.trial-credit}（默认 3）条体验额度）。{@code login} 自身是 {@code @Transactional}，
 *       钩子 ({@code REQUIRED}) 加入 login 事务——app_user 插入与钩子三步同生共死；钩子失败则 app_user
 *       一并回滚，重试登录 {@code isNew=true} 重新触发钩子，{@code credit} 幂等不重复入账。
 *   <li><b>SMS 发送留桩</b>：MVP 期只把验证码写入 {@code sms_code} 表 + 日志，不调真实网关（联调时替换）。
 * </ul>
 *
 * <p>本服务的额度相关操作仅限注册钩子一次性赠送体验额度（免费）——登录链路本身不扣费、不引入跨服务对账。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 验证码有效期 5 分钟。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 连续错误 5 次进入锁定。 */
    private static final int LOCK_ERR_THRESHOLD = 5;
    /** 锁定时长 10 分钟（比验证码 5 分钟有效期长，覆盖整段锁定语义）。 */
    private static final Duration LOCK_WINDOW = Duration.ofMinutes(10);

    private static final int RATE_LIMIT_1MIN = 1;
    private static final int RATE_LIMIT_1HOUR = 5;
    private static final int RATE_LIMIT_24HOUR = 10;

    private final SmsCodeMapper smsCodeMapper;
    private final AppUserMapper appUserMapper;
    private final JwtUtil jwtUtil;
    private final RechargeOrderService rechargeOrderService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            SmsCodeMapper smsCodeMapper,
            AppUserMapper appUserMapper,
            JwtUtil jwtUtil,
            RechargeOrderService rechargeOrderService) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.jwtUtil = jwtUtil;
        this.rechargeOrderService = rechargeOrderService;
    }

    /** 登录成功后的返回体：JWT、用户 id、是否本次新建的 app_user。 */
    public record LoginResult(String token, Long userId, boolean isNew) {}

    /**
     * 发送验证码：先查锁定 → 再查三级频控 → 生成 6 位码落库 → 留桩日志。
     *
     * <p>锁定期间发新码也被拒绝（避免攻击者用发新码绕开 err_count 累积）。
     */
    public void sendCode(String phone) {
        checkLocked(phone);
        checkRateLimit(phone);

        String code = generateCode();
        SmsCode row = new SmsCode();
        row.setPhone(phone);
        row.setCode(code);
        row.setExpireAt(OffsetDateTime.now().plus(CODE_TTL));
        // err_count / used / created_at 走 DB 默认值
        smsCodeMapper.insert(row);

        // MVP 留桩：联调时替换为阿里云 SMS。验证码已在 sms_code 表中，测试可直查。
        log.info("[SMS-STUB] send code to phone={}: code={} (ttl={}min)", phone, code, CODE_TTL.toMinutes());
    }

    /**
     * 校验验证码登录：锁定判定 → 取最近未使用未过期码比对 → 错误 err_count+1（达 5 进入锁定）→
     * 成功标 used + upsert app_user + 签发 user JWT。
     *
     * <p>本方法 {@code @Transactional}：app_user 插入 + 注册钩子（{@link
     * RechargeOrderService#onUserRegistered} 的 3 步：ensureAccount + createTrialOrder +
     * credit）在同一事务内原子提交。若钩子失败（如瞬时 DB 错误），app_user 插入一并回滚——
     * 用户重新发码登录时 {@code findByPhone} 返回 null → {@code isNew=true} → 钩子重新触发，
     * {@link CreditService#credit} 的 {@code (biz_id, biz_type, type)} 幂等保证重试不重复入账
     * （若首次部分提交了 trial credit，重试静默 no-op；若全回滚则重试重新赠送——两种情况均正确）。
     *
     * <p>事务内全是 DB 操作（sms_code markUsed / incrementErrCount / app_user insert / 钩子 3 步），
     * 无外部 HTTP 调用（SMS 发送在 {@link #sendCode}，不在本方法），持锁时长亚秒级，安全。
     */
    @Transactional
    public LoginResult login(String phone, String code) {
        checkLocked(phone);

        SmsCode active = smsCodeMapper.findActiveCode(phone);
        if (active == null) {
            // 没有可用码（未发、已用、已过期）——直接报错，无 err_count 可累加
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            // 错误码：err_count +1，达 5 即进入锁定（下次任意尝试即被锁），本次仍报 INVALID
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 成功：标 used，避免重放
        smsCodeMapper.markUsed(active.getId());

        boolean isNew = false;
        AppUser user = appUserMapper.findByPhone(phone);
        if (user == null) {
            user = new AppUser();
            user.setPhone(phone);
            // default_platform / profile_completeness / token_version 走 DB 默认值
            appUserMapper.insert(user);
            isNew = true;
            // 注册钩子（跨 bean @Transactional(REQUIRED)）：建账 + trial 单 + 送体验额度。加入 login 的事务，
            // 与 app_user 插入同生共死——钩子失败则 app_user 一并回滚，重试登录 isNew=true 触发重新挂账
            // （credit 幂等保证不重复入账）。
            rechargeOrderService.onUserRegistered(user.getId());
        }
        String token = jwtUtil.issue(user.getId(), "user", user.getTokenVersion() == null ? 0 : user.getTokenVersion());
        return new LoginResult(token, user.getId(), isNew);
    }

    /**
     * 三级频控：1 分钟 ≤1、1 小时 ≤5、24 小时 ≤10。任一窗口超限即抛 {@link
     * ErrorCode#SMS_RATE_LIMIT}。纯 SQL 滚动窗口聚合，无 Redis。
     */
    public void checkRateLimit(String phone) {
        if (smsCodeMapper.countLastMinute(phone) >= RATE_LIMIT_1MIN
                || smsCodeMapper.countLastHour(phone) >= RATE_LIMIT_1HOUR
                || smsCodeMapper.countLast24Hours(phone) >= RATE_LIMIT_24HOUR) {
            throw new BizException(ErrorCode.SMS_RATE_LIMIT);
        }
    }

    /**
     * 锁定判定：最近一条码 {@code err_count >= 5} 且 {@code created_at} 距今不足 10 分钟即视为锁定。
     * 复用已有列，无需新列；锁定期间不可登录、不可发新码。
     */
    private void checkLocked(String phone) {
        SmsCode latest = smsCodeMapper.findMostRecent(phone);
        if (latest == null) {
            return;
        }
        if (latest.getErrCount() != null
                && latest.getErrCount() >= LOCK_ERR_THRESHOLD
                && latest.getCreatedAt() != null
                && latest.getCreatedAt().isAfter(OffsetDateTime.now().minus(LOCK_WINDOW))) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
    }

    /** 生成 6 位数字验证码（首位允许 0，左补零到 6 位）。 */
    private String generateCode() {
        int c = random.nextInt(1_000_000);
        return String.format("%06d", c);
    }
}
