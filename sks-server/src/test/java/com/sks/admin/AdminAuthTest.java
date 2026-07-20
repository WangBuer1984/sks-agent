package com.sks.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sks.AbstractDbTest;
import com.sks.common.BizException;
import com.sks.common.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 管理端登录 + token 隔离测试。
 *
 * <p>覆盖三条核心场景：
 *
 * <ol>
 *   <li>用 AdminSeedRunner 种子回填后的真实账号能登录成功
 *   <li>错误密码抛 ADMIN_UNAUTHORIZED
 *   <li>C 端 user token 访问 /api/admin/** 被 AdminJwtFilter 拒绝（401）—— audience 隔离
 * </ol>
 *
 * <p>{@code @TestPropertySource} 注入 ADMIN_SEED_USERNAME/PASSWORD，AdminSeedRunner 在上下文启动时
 * 回填真实站长账号；{@code @AutoConfigureMockMvc} 让 MockMvc 走完整 Spring Security 过滤链。
 */
@TestPropertySource(
        properties = {
            "ADMIN_SEED_USERNAME=admin",
            "ADMIN_SEED_PASSWORD=test-admin-pwd"
        })
@AutoConfigureMockMvc
class AdminAuthTest extends AbstractDbTest {

    @Autowired AdminUserService adminUserService;
    @Autowired JwtUtil jwtUtil;
    @Autowired MockMvc mockMvc;

    @Value("${ADMIN_SEED_USERNAME:admin}") String seedUsername;
    @Value("${ADMIN_SEED_PASSWORD:test-admin-pwd}") String seedPassword;

    @Test
    void adminLoginSucceedsWithSeededCredential() {
        var resp = adminUserService.login(seedUsername, seedPassword);
        assertNotNull(resp.token());
    }

    @Test
    void adminLoginFailsWithWrongPassword() {
        assertThrows(BizException.class, () -> adminUserService.login(seedUsername, "wrong"));
    }

    @Test
    void userTokenRejectedOnAdminEndpoint() throws Exception {
        // 用 user audience 签发一个 C 端 token，访问 admin 路由——AdminJwtFilter 用 admin audience
        // 解析会抛 audience mismatch/验签失败，不设置认证上下文 → AuthenticationEntryPoint 返回 401。
        // /api/admin/orders 路由尚不存在（Task 0.7），但过滤链在 DispatcherServlet 之前执行，不影响 401。
        String userToken = jwtUtil.issue(1L, "user", 0);
        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
    }

    /** 隔离反向：admin token 访问 C 端 /api/user/me 应被 UserJwtFilter 拒绝（admin audience 用 user key 解析失败）→ 401。 */
    @Test
    void adminTokenRejectedOnUserEndpoint() throws Exception {
        String adminToken = jwtUtil.issue(1L, "admin", 0);
        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    /** 无 token 访问受保护 admin 路由也应 401（MissingBearer → 不设置上下文 → entryPoint）。 */
    @Test
    void noTokenRejectedOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized());
    }
}
