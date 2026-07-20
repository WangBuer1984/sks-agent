package com.sks.config;

import com.sks.common.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 两条独立的 Spring Security 过滤器链：admin 链 + C 端 user 链，实现后台 / C 端 token 隔离。
 *
 * <p>设计要点（tech-design §6）：
 *
 * <ul>
 *   <li><b>admin 链 @Order(1)</b>：securityMatcher("/api/admin/**")，用 {@link AdminJwtFilter} 校验 admin
 *       audience。仅 {@code /api/admin/auth/login} 放行，其余需 admin JWT。
 *   <li><b>user 链 @Order(2)</b>：securityMatcher("/api/**")，用 {@link UserJwtFilter} 校验 user audience。
 *       {@code /api/health}、{@code /api/auth/**} 放行，其余需 user JWT。
 *   <li>admin 链 @Order 更小 → 更具体路径先匹配；user token 访问 /api/admin/** 时走 admin 链，被
 *       AdminJwtFilter 拒绝（user audience 解析失败）→ 401。反之 admin token 访问 /api/user/** 走 user 链
 *       被 UserJwtFilter 拒绝 → 401。两链 audience 不可互换。
 *   <li>两链均 STATELESS + CSRF disable，无 session、无 cookie；认证失败统一返回 HTTP 401
 *       （GlobalExceptionHandler 不覆盖 SecurityFilterChain 阶段的 401，故用 AuthenticationEntryPoint）。
 * </ul>
 *
 * <p>本类替换 Task 0.1 的最小 SecurityConfig（旧版只放 /api/health）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** admin 链：@Order(1) 先匹配，覆盖 /api/admin/**。 */
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, JwtUtil jwtUtil) throws Exception {
        http.securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new AdminJwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(eh -> eh.authenticationEntryPoint(unauthorizedEntryPoint()));
        return http.build();
    }

    /** user 链：@Order(2) 兜底其余 /api/**。 */
    @Bean
    @Order(2)
    public SecurityFilterChain userFilterChain(HttpSecurity http, JwtUtil jwtUtil) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new UserJwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(eh -> eh.authenticationEntryPoint(unauthorizedEntryPoint()));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 认证失败入口：统一返回 HTTP 401（过滤器不抛异常，由 authorizeHttpRequests 触发此入口）。 */
    private HttpStatusEntryPoint unauthorizedEntryPoint() {
        return new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }
}
