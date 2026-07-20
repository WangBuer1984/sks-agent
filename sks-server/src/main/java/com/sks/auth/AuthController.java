package com.sks.auth;

import com.sks.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端认证端点：发验证码 + 登录（即注册）。
 *
 * <p>路径在 {@code /api/auth/**} 下——Task 0.6 的 SecurityConfig 会放开此路径供未登录访问。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 发送验证码：写 sms_code，受三级频控与 5 次锁定约束。 */
    @PostMapping("/send-code")
    public ApiResponse<Void> sendCode(@Valid @RequestBody SendCodeRequest req) {
        authService.sendCode(req.phone());
        return ApiResponse.ok(null);
    }

    /** 登录（即注册）：校验验证码 → upsert app_user → 签发 user JWT。 */
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req.phone(), req.code()));
    }

    public record SendCodeRequest(@NotBlank String phone) {}

    public record LoginRequest(@NotBlank String phone, @NotBlank String code) {}
}
