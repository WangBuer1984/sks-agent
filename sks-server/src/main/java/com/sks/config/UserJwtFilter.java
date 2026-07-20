package com.sks.config;

import com.sks.common.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * C 端 JWT 过滤器：从 {@code Authorization: Bearer <token>} 取 token，用 user audience 解析验签。
 *
 * <p>解析成功 → 设置 SecurityContext（principal=subjectId(Long)、authority="user"），让下游
 * {@code @AuthenticationPrincipal Long} 注入用户 id。解析失败（无 header / 错误 audience / 过期 / 验签失败）
 * 则<strong>不设置</strong>认证上下文，交由 {@code AuthenticationEntryPoint} 返回 401——不抛异常，避免
 * 被 GlobalExceptionHandler 吞成 200/body。
 *
 * <p>MVP 不查库比对 token_version（避免每请求一次 DB 查询；claim 已带版本号，V1.1 需即时失效时补一行比对）。
 */
public class UserJwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUDIENCE = "user";

    private final JwtUtil jwtUtil;

    public UserJwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractBearer(request);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token, AUDIENCE);
                Long subjectId = Long.parseLong(claims.getSubject());
                List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority(AUDIENCE));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(subjectId, null, auths);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (RuntimeException ignored) {
                // parse 失败（错误 audience / 过期 / 验签失败 / 格式异常）——不设置认证上下文
                // 让 authorizeHttpRequests 的 AuthenticationEntryPoint 返回 401
            }
        }
        chain.doFilter(request, response);
    }

    /** 提取 Bearer token；缺失或格式不符返回 null（不视为错误，permitAll 路径仍可放行）。 */
    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
