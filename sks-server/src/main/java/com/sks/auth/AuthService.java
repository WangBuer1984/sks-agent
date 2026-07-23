package com.sks.auth;

import com.sks.admin.RechargeOrderService;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.JwtUtil;
import com.sks.common.SmsClient;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
 *       {@code sks.trial-credit}（默认 3）条体验额度）。<b>事务边界（关键）</b>：{@link #login} 本身
 *       <b>非</b> {@code @Transactional}——错码路径 {@code incrementErrCount + throw BizException} 必须在
 *       无事务下执行，让 {@code err_count} 自增即时提交、5 次后锁定真正生效（{@code @Transactional} 会在
 *       抛 {@code BizException} 时回滚 {@code err_count} 自增 → 锁定形同虚设）。正确码路径委托给
 *       {@link #completeLogin}（{@code @Transactional}，经 self 注入的 Spring 代理调用，非 {@code this.}
 *       自调用）：{@code markUsed} + {@code app_user} 插入 + 钩子三步 + 签发 JWT 原子提交；钩子失败则全回滚
 *       （含 {@code markUsed} + {@code app_user}），重试登录 {@code findByPhone} 返回 null → {@code isNew=true}
 *       重新触发钩子，{@code credit} 的 {@code (biz_id, biz_type, type)} 幂等保证重试不重复入账。
 *   <li><b>SMS 发送</b>：MVP 期只把验证码写入 {@code sms_code} 表，经 {@link SmsClient} seam 发送（条件降级 + 真
 *       SendSms）——key 空→stub 不抛；configured→真 SendSms，失败抛 SMS_SEND_FAILED 透传 controller 5003。
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
    private final SmsClient smsClient;
    /**
     * Self 代理（{@code @Lazy} 注入破除构造期循环依赖）。用于在 {@link #login}（非事务）中通过 Spring 代理
     * 调用 {@link #completeLogin}（{@code @Transactional}）——直接 {@code this.completeLogin(...)} 自调用会
     * 绕过代理使 {@code @Transactional} 失效（原子性回归）。{@code @Lazy} 保证首次使用时才解析代理，
     * 不影响启动顺序。
     */
    private final AuthService self;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            SmsCodeMapper smsCodeMapper,
            AppUserMapper appUserMapper,
            JwtUtil jwtUtil,
            RechargeOrderService rechargeOrderService,
            @Lazy AuthService self,
            SmsClient smsClient) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.jwtUtil = jwtUtil;
        this.rechargeOrderService = rechargeOrderService;
        this.self = self;
        this.smsClient = smsClient;
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

        // §3 联调首检「阿里云短信」：经 SmsClient seam 发送（key 空→stub 不抛；configured→真 SendSms，
        // 失败抛 SMS_SEND_FAILED 透传 controller 5003）。sms_code 行已落，验证码可直查。
        smsClient.sendVerificationCode(phone, code);
    }

    /**
     * 校验验证码登录：锁定判定 → 取最近未使用未过期码比对 → 错误码 {@code err_count+1}（达 5 进入锁定）→
     * 正确码委托 {@link #completeLogin} 原子完成「标 used + upsert app_user + 注册钩子 + 签发 JWT」。
     *
     * <p><b>本方法故意非 {@code @Transactional}</b>（Critical 回归修复）：错码路径
     * {@code incrementErrCount + throw BizException} 必须在<b>无事务</b>下执行——{@code incrementErrCount}
     * 是普通 {@code @Update} Mapper，无外层事务时即自动提交，{@code err_count} 落库；随后抛出的
     * {@link BizException}（unchecked）在无事务下不触发任何回滚。若把 {@code login} 标
     * {@code @Transactional}，则 {@code BizException} 会触发回滚 → {@code err_count} 自增被回滚 →
     * {@code checkLocked}（读 {@code err_count >= 5}）<b>永不触发</b> → 攻击者在验证码 5 分钟 TTL 内可
     * 无限暴破 6 位码。此回归曾被 {@link AbstractDbTest} 的 {@code @Transactional} 测试基类掩盖（测试事务
     * 在同连接上读到未提交的自增、方法结束才整体回滚），故需非事务锁测试 {@code fiveWrongAttemptsLockPersistsAcrossTransactions}
     * 兜底。
     *
     * <p>正确码路径走 {@link #completeLogin}（经 {@code self} 代理调用，{@code @Transactional} 生效），
     * 保证 {@code markUsed + app_user 插入 + 钩子} 原子提交——钩子失败则全回滚，重试登录 {@code isNew=true}
     * 重触发钩子，{@code credit} 幂等不重复入账。
     */
    public LoginResult login(String phone, String code) {
        checkLocked(phone);

        SmsCode active = smsCodeMapper.findActiveCode(phone);
        if (active == null) {
            // 没有可用码（未发、已用、已过期）——直接报错，无 err_count 可累加
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            // 错码：err_count +1（无外层事务 → 即时自动提交，达 5 即进入锁定），本次仍报 INVALID。
            // 切勿在此方法上加 @Transactional——否则回滚 err_count 使锁定形同虚设。
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 正确码：委托给 @Transactional 代理方法，使 markUsed + app_user 插入 + 注册钩子 + JWT 原子提交。
        // 必须走 self 代理（self.completeLogin），不可 this.completeLogin——自调用绕过 Spring 代理，
        // @Transactional 失效，原子性回归。
        return self.completeLogin(active, phone);
    }

    /**
     * 正确码后的原子收尾（{@code @Transactional}，必须经 {@code self} 代理调用）：
     *
     * <ol>
     *   <li>{@code markUsed}：标该码已用，防重放。
     *   <li>{@code findByPhone}：手机号存在则直接登录；不存在则插 {@code app_user}（{@code isNew=true}）。
     *   <li>仅 {@code isNew=true} 时触发注册钩子 {@link RechargeOrderService#onUserRegistered}
     *       （{@code REQUIRED} 加入本事务）：建账 + trial 单 + 送体验额度。
     *   <li>签发 user JWT。
     * </ol>
     *
     * <p>钩子失败 → 全 tx 回滚（含 {@code markUsed} + {@code app_user} 插入）→ 用户重试登录时
     * {@code findByPhone} 仍返回 null → {@code isNew=true} → 钩子重新触发，{@code credit} 幂等保证
     * 不重复入账（首次若部分提交则静默 no-op；若全回滚则重新赠送——两情况均正确）。事务内纯 DB
     * 操作（无外部 HTTP 调用），持锁时长亚秒级，安全。
     */
    @Transactional
    public LoginResult completeLogin(SmsCode active, String phone) {
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
            // 注册钩子（跨 bean @Transactional(REQUIRED)）：建账 + trial 单 + 送体验额度。加入 completeLogin 事务，
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
