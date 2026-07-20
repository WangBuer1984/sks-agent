package com.sks.config;

import com.sks.common.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link JwtUtil} 注册为 Spring Bean，从环境变量取 C 端 / 管理端两把签名密钥。
 *
 * <p>本任务只建 Bean，<strong>不修改</strong> {@link SecurityConfig}（Task 0.6 拥有它，会替换为完整
 * 两条 SecurityFilterChain）。默认值仅供本地/测试回退，生产必须显式注入 ≥32 字节密钥。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(
            @Value("${JWT_SECRET_USER:user-secret-32bytes-xxxxxxxxxxxxx}") String userSecret,
            @Value("${JWT_SECRET_ADMIN:admin-secret-32bytes-xxxxxxxxxxx}") String adminSecret) {
        return new JwtUtil(userSecret, adminSecret);
    }
}
