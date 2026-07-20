package com.sks.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时种子回填：把 Flyway V2 插入的 {@code __seed__}/PLACEHOLDER 占位行，用环境变量配置的真实密码
 * BCrypt 哈希后 upsert 为可登录的站长账号。
 *
 * <p>触发条件：admin_user 中存在 {@code username='__seed__'} 或 {@code password_hash='PLACEHOLDER'} 的占位行
 * （由 Flyway V2 保证至少一条）。命中后用 {@code ADMIN_SEED_USERNAME}（默认 "admin"）作为账号名，
 * {@code ADMIN_SEED_PASSWORD} 环境变量的值经 BCrypt 哈希后写入 password_hash，upsert（ON CONFLICT
 * username DO UPDATE）保证重跑幂等且每次重新哈希。
 *
 * <p>安全注意：
 *
 * <ul>
 *   <li>{@code ADMIN_SEED_PASSWORD} 缺失/空白 → 仅 WARN 日志，不崩启动（避免本地/CI 无密码时启动失败）。
 *   <li>占位行 {@code __seed__} 不被删除——它作为「是否已初始化」的哨兵，且其 password_hash=PLACEHOLDER
 *       永远无法通过 BCrypt.matches（AdminUserService 会吞掉异常并返回 401）。
 * </ul>
 */
@Component
public class AdminSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedRunner.class);

    private static final String SEED_NAME = "站长本人";
    private static final String SEED_STATUS = "active";

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final String seedUsername;
    private final String seedPassword;

    public AdminSeedRunner(
            AdminUserMapper adminUserMapper,
            PasswordEncoder passwordEncoder,
            @Value("${ADMIN_SEED_USERNAME:admin}") String seedUsername,
            @Value("${ADMIN_SEED_PASSWORD:}") String seedPassword) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.seedUsername = seedUsername;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.isBlank()) {
            log.warn(
                    "[AdminSeed] ADMIN_SEED_PASSWORD 未设置——跳过种子回填。"
                            + "请设置该环境变量以激活 __seed__ 占行，否则无法登录管理后台。");
            return;
        }
        AdminUser placeholder = adminUserMapper.findPlaceholder();
        if (placeholder == null) {
            // 无占位行：认为已手动配置或已回填过（非 PLACEHOLDER），不做任何操作
            return;
        }
        String hash = passwordEncoder.encode(seedPassword);
        adminUserMapper.upsertSeed(seedUsername, hash, SEED_NAME, SEED_STATUS);
        log.info(
                "[AdminSeed] 种子回填完成：admin_user.username='{}'（占位行命中，密码已从 ADMIN_SEED_PASSWORD 哈希）",
                seedUsername);
    }
}
