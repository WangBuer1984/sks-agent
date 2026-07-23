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
import org.springframework.stereotype.Service;

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
    private final SecureRandom random = new SecureRandom();

    public UserPhoneService(SmsCodeMapper smsCodeMapper, AppUserMapper appUserMapper,
                            PhoneChangeSessionMapper sessionMapper, SmsClient smsClient) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.sessionMapper = sessionMapper;
        this.smsClient = smsClient;
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
