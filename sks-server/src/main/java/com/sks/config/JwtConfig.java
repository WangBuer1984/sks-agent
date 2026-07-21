package com.sks.config;

import com.sks.common.JwtUtil;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link JwtUtil} 注册为 Spring Bean，从环境变量取 C 端 / 管理端两把签名密钥。
 *
 * <p>本任务只建 Bean，<strong>不修改</strong> {@link SecurityConfig}（Task 0.6 拥有它，会替换为完整
 * 两条 SecurityFilterChain）。<strong>无回退默认值</strong>——若 {@code JWT_SECRET_USER} /
 * {@code JWT_SECRET_ADMIN} 环境变量缺失、为空、或仍是 {@code .env.example} 的占位字符串（或旧的硬编码
 * 回退值），启动即抛 {@link IllegalStateException} 失败。绝不以已知/可伪造的密钥启动生产。
 */
@Configuration
public class JwtConfig {

    /**
     * 已知的占位 / 旧回退密钥——出现即视为未配置，启动失败。
     *
     * <p>匹配 {@code .env.example} 的两个占位值，以及本类历史上硬编码的两个回退默认值
     * （均为合法 ≥32 字节 HS256 密钥，可被攻击者直接伪造 token，必须拒绝）。
     */
    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "change_me_user_secret_min_32_bytes",
            "change_me_admin_secret_min_32_bytes",
            "user-secret-32bytes-xxxxxxxxxxxxx",
            "admin-secret-32bytes-xxxxxxxxxxx");

    @Bean
    public JwtUtil jwtUtil(
            @Value("${JWT_SECRET_USER:}") String userSecret,
            @Value("${JWT_SECRET_ADMIN:}") String adminSecret) {
        guardSecret(userSecret, "JWT_SECRET_USER");
        guardSecret(adminSecret, "JWT_SECRET_ADMIN");
        return new JwtUtil(userSecret, adminSecret);
    }

    /**
     * 校验单把密钥：非空、且非已知占位字符串。否则启动失败。
     */
    private static void guardSecret(String secret, String envName) {
        if (secret == null || secret.isBlank() || KNOWN_PLACEHOLDERS.contains(secret)) {
            throw new IllegalStateException(
                    envName + " must be set to a real ≥32-byte secret (got blank/placeholder)");
        }
    }
}
