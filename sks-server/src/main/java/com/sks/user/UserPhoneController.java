package com.sks.user;

import com.sks.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 换绑手机号端点（spec §5.2，/api/user/phone/change/**，需 C 端 JWT）。 */
@RestController
@RequestMapping("/api/user/phone/change")
public class UserPhoneController {

    private final UserPhoneService userPhoneService;

    public UserPhoneController(UserPhoneService userPhoneService) {
        this.userPhoneService = userPhoneService;
    }

    @PostMapping("/send-old-code")
    public ApiResponse<Void> sendOldCode(@AuthenticationPrincipal Long userId) {
        userPhoneService.sendOldPhoneCode(userId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify-old")
    public ApiResponse<TokenResponse> verifyOld(@AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody VerifyCodeRequest req) {
        String token = userPhoneService.verifyOldPhone(userId, req.code());
        return ApiResponse.ok(new TokenResponse(token));
    }

    @PostMapping("/send-new-code")
    public ApiResponse<Void> sendNewCode(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody NewPhoneRequest req) {
        userPhoneService.sendNewPhoneCode(userId, req.newPhone(), req.token());
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify-new")
    public ApiResponse<Void> verifyNew(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody VerifyNewRequest req) {
        userPhoneService.verifyNewPhone(userId, req.newPhone(), req.code(), req.token());
        return ApiResponse.ok(null);
    }

    public record VerifyCodeRequest(@NotBlank String code) {}
    public record TokenResponse(String token) {}
    public record NewPhoneRequest(@NotBlank String newPhone, @NotBlank String token) {}
    public record VerifyNewRequest(@NotBlank String newPhone, @NotBlank String code, @NotBlank String token) {}
}
