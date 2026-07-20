package com.sks.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;


/**
 * JWT 工具：C 端 / 管理端使用各自独立的签名密钥，且 {@link #parse} 强制校验 audience 匹配。
 *
 * <p>这是 C 端与后台 token 隔离的技术底座：
 *
 * <ul>
 *   <li>用 user key 签发的 token 只能用 audience="user" 解析
 *   <li>用 admin key 签发的 token 只能用 audience="admin" 解析
 *   <li>跨 audience 解析（如 user token 当 admin 用）在验签或 aud 校验阶段抛 {@link RuntimeException}
 * </ul>
 *
 * <p>两个密钥必须 ≥32 字节以满足 HS256 的最低强度要求。
 */
public class JwtUtil {

    private static final Set<String> ALLOWED_AUDIENCES = Set.of("user", "admin");

    private final SecretKey userKey;
    private final SecretKey adminKey;

    /**
     * @param userSecret  C 端签名密钥（≥32 字节）
     * @param adminSecret 管理端签名密钥（≥32 字节）
     */
    public JwtUtil(String userSecret, String adminSecret) {
        byte[] userBytes = userSecret.getBytes(StandardCharsets.UTF_8);
        byte[] adminBytes = adminSecret.getBytes(StandardCharsets.UTF_8);
        if (userBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT_SECRET_USER must be >= 32 bytes for HS256, got " + userBytes.length);
        }
        if (adminBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT_SECRET_ADMIN must be >= 32 bytes for HS256, got " + adminBytes.length);
        }
        this.userKey = Keys.hmacShaKeyFor(userBytes);
        this.adminKey = Keys.hmacShaKeyFor(adminBytes);
    }

    /**
     * 按 audience 选取密钥签发 token，subject=用户/管理员 id，tokenVersion 写入 claim 供后续踢人。
     *
     * <p>过期时间按 audience 内置：C 端 7 天、管理端 24h（Task 0.6 决策 #1）。不改变 3 参签名（Task
     * 0.4 AuthService.login 已锁定该契约），把 expiry 作为 JwtUtil 内部关注点。jjjwt 的 {@link #parse}
     * 在 exp 已过期时自动抛 {@link io.jsonwebtoken.ExpiredJwtException}（RuntimeException 子类），过滤器
     * 捕获后按 401 处理。
     */
    public String issue(long subjectId, String audience, int tokenVersion) {
        SecretKey key = keyForAudience(audience);
        Duration expiry = "admin".equals(audience) ? Duration.ofHours(24) : Duration.ofDays(7);
        // jjjwt 0.12.5: 用 setAudience 设置单值 aud；audience() 是 AudienceCollection 的 no-arg getter。
        return Jwts.builder()
                .subject(String.valueOf(subjectId))
                .setAudience(audience)
                .claim("ver", tokenVersion)
                .expiration(Date.from(Instant.now().plus(expiry)))
                .signWith(key)
                .compact();
    }

    /**
     * 用 audience 对应的密钥验签并校验 token 的 aud 与请求的 audience 一致，不一致则抛 {@link
     * RuntimeException}。
     */
    public Claims parse(String token, String audience) {
        SecretKey key = keyForAudience(audience);
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        // jjjwt 0.12.5: Claims.getAudience() 返回 Set<String>。用错的密钥验签会先抛 JwtException
        // （被当作 RuntimeException 的子类），这里再兜一层 aud 一致性校验。
        Set<String> tokenAud = claims.getAudience();
        if (tokenAud == null || !tokenAud.contains(audience)) {
            throw new RuntimeException(
                    "audience mismatch: expected " + audience + " but token aud=" + tokenAud);
        }
        return claims;
    }

    private SecretKey keyForAudience(String audience) {
        if (!ALLOWED_AUDIENCES.contains(audience)) {
            throw new IllegalArgumentException("unsupported audience: " + audience);
        }
        return "user".equals(audience) ? userKey : adminKey;
    }
}
