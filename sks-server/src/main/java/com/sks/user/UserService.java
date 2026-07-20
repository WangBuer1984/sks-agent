package com.sks.user;

import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人资料服务：读取 {@code /me}、更新基础资料+创作资料+主平台并重算 completeness。
 *
 * <p>completeness 口径（PRD §4.3 的有意简化）：<strong>只按创作资料 5 字段算</strong>——
 * nickname / industry / identity / style / weeklyGoal 已填数 ÷ 5 × 100（取整）。
 * 基础资料（gender/age/city）与主平台不计入分母——它们影响生成质量但不构成「可创作」的下限。
 *
 * <p>{@link #me} 返回的 {@code balance} 当前硬编码为 0 占位——{@code CreditService.balance} 在
 * Task 0.5 才建，本任务先返回 0，Task 0.5 完成后一行接线替换。
 */
@Service
public class UserService {

    private final AppUserMapper appUserMapper;

    public UserService(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    /** {@code GET /api/user/me} 的返回体。{@code balance} 占位 0，Task 0.5 接线真实余额。 */
    public record MeResponse(
            Long userId,
            String phone,
            String nickname,
            String gender,
            Integer age,
            String city,
            String industry,
            String identity,
            String style,
            Integer weeklyGoal,
            String defaultPlatform,
            Integer completeness,
            Integer balance) {}

    /** 读取当前用户资料。未找到抛 {@link ErrorCode#UNAUTHORIZED}（理论上不会发生，安全层已校验）。 */
    public MeResponse me(long userId) {
        AppUser u = appUserMapper.selectById(userId);
        if (u == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // TODO Task 0.5: 接线 CreditService.balance(userId)，替换占位 0
        int balance = 0;
        return new MeResponse(
                u.getId(),
                u.getPhone(),
                u.getNickname(),
                u.getGender(),
                u.getAge(),
                u.getCity(),
                u.getIndustry(),
                u.getIdentity(),
                u.getStyle(),
                u.getWeeklyGoal(),
                u.getDefaultPlatform(),
                u.getProfileCompleteness() == null ? 0 : u.getProfileCompleteness(),
                balance);
    }

    /**
     * 更新资料：非空字段覆盖落库，重算 completeness（5 创作资料字段已填数 / 5 × 100）。
     * 只更新非 null 字段——避免误把显式 null 写成 NULL。
     */
    @Transactional
    public MeResponse update(long userId, UpdateMe req) {
        AppUser u = appUserMapper.selectById(userId);
        if (u == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (req.nickname() != null) u.setNickname(req.nickname());
        if (req.gender() != null) u.setGender(req.gender());
        if (req.age() != null) u.setAge(req.age());
        if (req.city() != null) u.setCity(req.city());
        if (req.industry() != null) u.setIndustry(req.industry());
        if (req.identity() != null) u.setIdentity(req.identity());
        if (req.style() != null) u.setStyle(req.style());
        if (req.weeklyGoal() != null) u.setWeeklyGoal(req.weeklyGoal());
        if (req.defaultPlatform() != null) u.setDefaultPlatform(req.defaultPlatform());

        u.setProfileCompleteness(computeCompleteness(u));
        appUserMapper.updateById(u);
        return me(userId);
    }

    /** completeness = (nickname/industry/identity/style/weeklyGoal 已填数) × 100 / 5。 */
    private int computeCompleteness(AppUser u) {
        int filled = 0;
        if (u.getNickname() != null && !u.getNickname().isBlank()) filled++;
        if (u.getIndustry() != null && !u.getIndustry().isBlank()) filled++;
        if (u.getIdentity() != null && !u.getIdentity().isBlank()) filled++;
        if (u.getStyle() != null && !u.getStyle().isBlank()) filled++;
        if (u.getWeeklyGoal() != null) filled++;
        return filled * 100 / 5;
    }
}
