package com.sks.admin;

import com.sks.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端认证端点：仅登录，无注册。
 *
 * <p>路径 {@code /api/admin/auth/login} 在 admin SecurityFilterChain 中放行（permitAll），
 * 供未登录访问；其余 /api/admin/** 均需 admin audience JWT。
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminUserService adminUserService;

    public AdminAuthController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** 管理端登录：校验账号密码 → 签发 admin JWT + 更新 last_login_at。 */
    @PostMapping("/login")
    public ApiResponse<AdminUserService.LoginResult> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(adminUserService.login(req.username(), req.password()));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
