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
 * 管理端 JWT 过滤器：从 {@code Authorization: Bearer <token>} 取 token，用 admin audience 解析验签。
 *
 * <p>与 {@link UserJwtFilter} 对称，但 audience="admin" + authority="admin"，且依赖 JwtUtil 的 admin
 * 独立签名密钥——<strong>C 端 user token 无法通过本过滤器解析</strong>（验签失败 / audience 不匹配），
 * 这是 admin/C端 token 隔离的技术保证（见 {@code userTokenRejectedOnAdminEndpoint} 测试）。
 *
 * <p>解析失败时不设置认证上下文，交由 admin 链的 AuthenticationEntryPoint 返回 401。
 */
public class AdminJwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUDIENCE = "admin";

    private final JwtUtil jwtUtil;

    public AdminJwtFilter(JwtUtil jwtUtil) {
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
                // parse 失败（user token 当 admin 用 / 过期 / 验签失败）——不设置认证上下文
            }
        }
        chain.doFilter(request, response);
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
