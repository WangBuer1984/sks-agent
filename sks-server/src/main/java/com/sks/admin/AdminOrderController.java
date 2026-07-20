package com.sks.admin;

import com.sks.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单端点：尾号搜用户、订单列表、开通、补偿。
 *
 * <p>路径 {@code /api/admin/orders}、{@code /api/admin/compensate}、{@code /api/admin/users} 均在
 * admin SecurityFilterChain 下（{@link com.sks.config.AdminJwtFilter} 校验 admin audience），无需新增
 * SecurityConfig。{@code adminUserId} 从 admin JWT principal（{@link Long}）注入。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminOrderController {

    private final RechargeOrderService rechargeOrderService;

    public AdminOrderController(RechargeOrderService rechargeOrderService) {
        this.rechargeOrderService = rechargeOrderService;
    }

    /**
     * 按手机尾号搜用户（管理端「尾号搜索 → 多人时逐一确认 → 开通」入口）。
     *
     * @param phoneTail 手机尾号（后 4-6 位）
     * @return {@code [{userId, phoneMasked, balance, latestOrderStatus}]}
     */
    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> searchUsers(
            @RequestParam("phoneTail") @NotBlank String phoneTail) {
        return ApiResponse.ok(rechargeOrderService.searchUsersByPhoneTail(phoneTail));
    }

    /**
     * 订单列表（含 user 手机尾号、套餐、状态、操作人）。
     *
     * @param status 状态过滤（trial/done；null 或空 → 全部）
     */
    @GetMapping("/orders")
    public ApiResponse<List<Map<String, Object>>> listOrders(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(rechargeOrderService.listOrders(status));
    }

    /**
     * 开通：trial→done（首充 + bonus 10）或复购新建 done 单（无 bonus）。返回更新后余额。
     *
     * @param adminUserId 操作管理员（admin JWT principal）
     */
    @PostMapping("/orders/open")
    public ApiResponse<Integer> open(
            @Valid @RequestBody OpenRequest req,
            @AuthenticationPrincipal Long adminUserId) {
        return ApiResponse.ok(rechargeOrderService.open(req.userId(), req.pkg(), adminUserId));
    }

    /**
     * 补偿额度：建 {@code order_type='compensate'} 单留痕 + credit。返回更新后余额。
     * 补偿单不参与首充判定。
     *
     * @param adminUserId 操作管理员（admin JWT principal）
     */
    @PostMapping("/compensate")
    public ApiResponse<Integer> compensate(
            @Valid @RequestBody CompensateRequest req,
            @AuthenticationPrincipal Long adminUserId) {
        return ApiResponse.ok(
                rechargeOrderService.compensate(req.userId(), req.n(), req.memo(), adminUserId));
    }

    /** 开通请求体。 */
    public record OpenRequest(@NotNull Long userId, @NotBlank String pkg) {}

    /** 补偿请求体。 */
    public record CompensateRequest(
            @NotNull Long userId, @NotNull @Min(1) Integer n, String memo) {}
}
