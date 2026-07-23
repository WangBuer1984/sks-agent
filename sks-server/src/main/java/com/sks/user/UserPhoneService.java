package com.sks.user;

import com.sks.auth.SmsCode;
import com.sks.auth.SmsCodeMapper;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.SmsClient;
import com.sks.common.SmsScene;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 换绑手机号 2-step flow（spec §3.5）。需 C 端 JWT（userId 由 controller 从 principal 取）。
 *
 * <p>step1 send-old-code + step2 verify-old（本任务）；step3 send-new-code + step4 verify-new（Task 7 补）。
 * 事务边界见 spec §6：err_count 自增路径非事务；verify-old 对码 markUsed + UPDATE session 两条自动提交写。
 */
@Service
public class UserPhoneService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);

    private final SmsCodeMapper smsCodeMapper;
    private final AppUserMapper appUserMapper;
    private final PhoneChangeSessionMapper sessionMapper;
    private final SmsClient smsClient;
    private final UserPhoneService self;
    private final SecureRandom random = new SecureRandom();

    public UserPhoneService(SmsCodeMapper smsCodeMapper, AppUserMapper appUserMapper,
                            PhoneChangeSessionMapper sessionMapper, SmsClient smsClient,
                            @Lazy UserPhoneService self) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.sessionMapper = sessionMapper;
        this.smsClient = smsClient;
        this.self = self;
    }

    /** step1：向当前手机号发码（VERIFY_OLD_PHONE）+ 建/覆 session。 */
    public void sendOldPhoneCode(long userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String oldPhone = user.getPhone();
        if (smsCodeMapper.existsLocked(oldPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        checkRateLimit(oldPhone);

        String code = generateCode();
        SmsCode row = new SmsCode();
        row.setPhone(oldPhone);
        row.setCode(code);
        row.setExpireAt(OffsetDateTime.now().plus(CODE_TTL));
        row.setScene(SmsScene.VERIFY_OLD_PHONE.name());
        smsCodeMapper.insert(row);

        // 先删后建（不标 DONE，部分唯一索引兜底并发）
        sessionMapper.deleteActiveByUserId(userId);
        PhoneChangeSession s = new PhoneChangeSession();
        s.setToken(UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(userId);
        s.setOldPhone(oldPhone);
        s.setStatus("AWAITING_OLD_VERIFY");
        s.setExpiresAt(OffsetDateTime.now().plus(SESSION_TTL));
        sessionMapper.insert(s);

        smsClient.sendVerificationCode(oldPhone, code, SmsScene.VERIFY_OLD_PHONE);
    }

    /** step2：校旧码 → 对则 markUsed + 置 AWAITING_NEW_VERIFY + 重置 expires_at + 返 token T。 */
    public String verifyOldPhone(long userId, String code) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String oldPhone = user.getPhone();
        if (smsCodeMapper.existsLocked(oldPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        PhoneChangeSession s = activeSessionOf(userId);
        if (s == null || !"AWAITING_OLD_VERIFY".equals(s.getStatus())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        SmsCode active = smsCodeMapper.findActiveCode(oldPhone, SmsScene.VERIFY_OLD_PHONE.name());
        if (active == null) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 对码：markUsed（防重放续窗口）+ 置 AWAITING_NEW_VERIFY + 重置 expires_at（new-bind 窗口从现在起算）
        smsCodeMapper.markUsed(active.getId());
        sessionMapper.updateToNewVerify(s.getId(), OffsetDateTime.now(), OffsetDateTime.now().plus(SESSION_TTL));
        return s.getToken();
    }

    /** step3：验 T → 校 newPhone → 频控+锁定 → 发码（BIND_NEW_PHONE）+ 落 new_phone。 */
    public void sendNewPhoneCode(long userId, String newPhone, String token) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        if (s == null || !s.getUserId().equals(userId)
                || !"AWAITING_NEW_VERIFY".equals(s.getStatus())
                || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (!isPresent(newPhone) || newPhone.equals(s.getOldPhone())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (appUserMapper.findByPhone(newPhone) != null) {
            throw new BizException(ErrorCode.PHONE_ALREADY_BOUND);
        }
        if (smsCodeMapper.existsLocked(newPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        checkRateLimit(newPhone);

        String code = generateCode();
        SmsCode row = new SmsCode();
        row.setPhone(newPhone);
        row.setCode(code);
        row.setExpireAt(OffsetDateTime.now().plus(CODE_TTL));
        row.setScene(SmsScene.BIND_NEW_PHONE.name());
        row.setSessionToken(token);
        smsCodeMapper.insert(row);
        sessionMapper.updateNewPhone(s.getId(), newPhone);

        smsClient.sendVerificationCode(newPhone, code, SmsScene.BIND_NEW_PHONE);
    }

    /** step4：验 T + 断言 newPhone==session.new_phone → 校新码 → 对码经 self 代理 @Transactional 收尾。 */
    public void verifyNewPhone(long userId, String newPhone, String code, String token) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        if (s == null || !s.getUserId().equals(userId)
                || !"AWAITING_NEW_VERIFY".equals(s.getStatus())
                || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (newPhone == null || !newPhone.equals(s.getNewPhone())) {
            // token 绑定特定 newPhone；不符 → 拒
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (smsCodeMapper.existsLocked(newPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        SmsCode active = smsCodeMapper.findActiveCode(newPhone, SmsScene.BIND_NEW_PHONE.name());
        if (active == null) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 对码：经 self 代理 @Transactional 收尾。UNIQUE 冲突透传出事务，在此（非事务调用方）catch。
        try {
            self.completePhoneChange(token, newPhone);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PHONE_ALREADY_BOUND);
        }
    }

    /**
     * 对码后原子收尾（@Transactional，必须经 self 代理调用）：
     * UPDATE app_user.phone + session.status=DONE + 作废 pending 码。UNIQUE 冲突抛 DuplicateKeyException 透传。
     */
    @Transactional
    public void completePhoneChange(String token, String newPhone) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        appUserMapper.updatePhone(s.getUserId(), newPhone);
        sessionMapper.markDone(s.getId());
        smsCodeMapper.invalidateByToken(token);
        smsCodeMapper.invalidateByPhones(s.getOldPhone(), newPhone);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private PhoneChangeSession activeSessionOf(long userId) {
        return sessionMapper.selectOne(  // 简单实现：遍历未完成，取第一条
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PhoneChangeSession>()
                        .eq("user_id", userId).ne("status", "DONE")
                        .orderByDesc("id").last("LIMIT 1"));
    }

    private void checkRateLimit(String phone) {
        if (smsCodeMapper.countLastMinute(phone) >= 1
                || smsCodeMapper.countLastHour(phone) >= 5
                || smsCodeMapper.countLast24Hours(phone) >= 10) {
            throw new BizException(ErrorCode.SMS_RATE_LIMIT);
        }
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
