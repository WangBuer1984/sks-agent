package com.sks.user;

import com.sks.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端个人资料端点：{@code /api/user/me} 的 GET/PUT。
 *
 * <p>用户 id 从 SecurityContext 的 principal 取（Task 0.6 的 SecurityFilterChain 会把 JWT subject
 * 注入为 {@code Long principal}）。本任务不修改 SecurityConfig——测试是服务级，不走 HTTP 层。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserService.MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(userService.me(userId));
    }

    @PutMapping("/me")
    public ApiResponse<UserService.MeResponse> update(
            @AuthenticationPrincipal Long userId, @RequestBody UpdateMe req) {
        return ApiResponse.ok(userService.update(userId, req));
    }
}
