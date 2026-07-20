package com.sks.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

/**
 * 纯 JUnit 单元测试：验证 C 端 / 管理端 token 隔离。
 * 不引入 Spring 上下文 / Testcontainers。
 *
 * <p>密钥均为 ≥32 字节以满足 jjjwt 0.12 HS256 的 {@code Keys.hmacShaKeyFor} 要求。
 */
class JwtUtilTest {

    @Test
    void userTokenCannotBeParsedAsAdmin() {
        // user-secret-32bytes-xxxxxxxxxxxxx  -> 33 bytes
        // admin-secret-32bytes-xxxxxxxxxxx  -> 32 bytes (原 brief 31 字节会因 WeakKeyException 失败，按 controller 指引补齐至 32)
        JwtUtil util =
                new JwtUtil(
                        "user-secret-32bytes-xxxxxxxxxxxxx",
                        "admin-secret-32bytes-xxxxxxxxxxx");
        String userToken = util.issue(1L, "user", 0);

        // C 端 token 用 admin audience 解析必须失败 —— 这是 token 隔离的技术底座
        assertThrows(RuntimeException.class, () -> util.parse(userToken, "admin"));

        // 用正确 audience 解析应返回签发时的 subject
        Claims claims = util.parse(userToken, "user");
        assertEquals(1L, Long.parseLong(claims.getSubject()));
    }
}
