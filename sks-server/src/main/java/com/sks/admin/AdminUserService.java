package com.sks.admin;

import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 管理端登录服务：BCrypt 校验 admin_user.password_hash，成功签发 admin audience JWT + 更新 last_login_at。
 *
 * <p>关键设计（与 tech-design §6 一致）：
 *
 * <ul>
 *   <li>admin token 用 <strong>不同 audience + 不同签名密钥</strong>（JwtUtil 双密钥），与 C 端 token 不可互换。
 *   <li>admin 无 token_version 列，{@link JwtUtil#issue} 第三参固定传 0；V1.1 若需即时失效再补列与比对。
 *   <li>用户不存在 / 密码错误 / 哈希格式异常 均抛 {@link ErrorCode#ADMIN_UNAUTHORIZED}——不区分「无此用户」
 *       与「密码错」以避免用户名枚举（尽力而为，非强约束）。
 * </ul>
 */
@Service
public class AdminUserService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AdminUserService(
            AdminUserMapper adminUserMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 登录成功后的返回体：admin JWT、管理员 id、姓名。 */
    public record LoginResult(String token, Long adminId, String name) {}

    /**
     * 校验用户名+密码：BCrypt.matches(rawPassword, password_hash)。成功则更新 last_login_at + 签发 admin JWT。
     * 失败（用户不存在 / 密码不匹配 / 哈希格式异常）抛 {@link BizException}（ADMIN_UNAUTHORIZED）。
     */
    public LoginResult login(String username, String password) {
        AdminUser admin = adminUserMapper.findByUsername(username);
        boolean ok = false;
        if (admin != null && admin.getPasswordHash() != null) {
            try {
                // BCrypt.matches 对格式不合法的 hash（如 PLACEHOLDER）会抛 IllegalArgumentException——
                // 一律视为登录失败，不向调用方泄露「哈希格式异常」与「密码错」的区别。
                ok = passwordEncoder.matches(password, admin.getPasswordHash());
            } catch (RuntimeException ignored) {
                ok = false;
            }
        }
        if (!ok) {
            throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        adminUserMapper.updateLastLoginAt(admin.getId());
        String token = jwtUtil.issue(admin.getId(), "admin", 0);
        return new LoginResult(token, admin.getId(), admin.getName());
    }
}
