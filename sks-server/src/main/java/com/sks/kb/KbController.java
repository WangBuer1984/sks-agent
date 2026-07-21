package com.sks.kb;

import com.sks.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端知识库管理端点 {@code /api/kb/cards}。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。四个端点：POST 新建、PUT 编辑、DELETE 删除、GET 列表。
 *
 * <p>返回 {@link ApiResponse}（code=0 成功）；BizException 由 {@link com.sks.common.GlobalExceptionHandler}
 * 统一翻译（如 CARD_IN_USE 带「有 N 篇稿件引用此卡」动态文案）。
 */
@RestController
@RequestMapping("/api/kb/cards")
public class KbController {

    private final KbCardService kbCardService;

    public KbController(KbCardService kbCardService) {
        this.kbCardService = kbCardService;
    }

    /** 新建卡片。layer ∈ {A,B,C}；B 层会同步算 embedding。 */
    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal Long userId, @RequestBody CreateCardRequest req) {
        long id = kbCardService.create(userId, req.layer(), req.cardType(), req.title(), req.content());
        return ApiResponse.ok(id);
    }

    /** 编辑卡片（改 title + content）。B 层会重算 embedding + 归档旧值。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @AuthenticationPrincipal Long userId, @PathVariable long id, @RequestBody UpdateCardRequest req) {
        kbCardService.update(userId, id, req.title(), req.content());
        return ApiResponse.ok(null);
    }

    /**
     * 删除卡片（软删）。有引用且 {@code force=false} 时抛 CARD_IN_USE；
     * {@code force=true} 强制软删。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean force) {
        kbCardService.delete(userId, id, force);
        return ApiResponse.ok(null);
    }

    /** 列出当前用户的未删卡片（可选 layer=A/B/C 过滤）。 */
    @GetMapping
    public ApiResponse<List<CardSummary>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "layer", required = false) String layer) {
        return ApiResponse.ok(kbCardService.list(userId, layer));
    }

    /** 新建请求体。content 为 JSON 文本（存 JSONB）。 */
    public record CreateCardRequest(String layer, String cardType, String title, String content) {}

    /** 编辑请求体。 */
    public record UpdateCardRequest(String title, String content) {}
}
