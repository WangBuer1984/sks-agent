package com.sks.topic;

import com.sks.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端选题端点 {@code /api/topics}。
 *
 * <p>落在 user SecurityFilterChain（{@code /api/**} 需 user JWT），{@code @AuthenticationPrincipal Long userId}
 * 由 {@link com.sks.config.UserJwtFilter} 注入。三个端点：GET 列表（四路聚合，Task 1.7）、GET 详情、
 * POST 新建（title 过 UGC 安全）。
 */
@RestController
@RequestMapping("/api/topics")
public class TopicController {

    private final TopicService topicService;

    public TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    /**
     * 当前用户的选题列表（四路聚合，按 pillar 排序）。
     *
     * <p>{@code source} 可选：传 {@code hot/faq/benchmark/replay} 之一 → 单路过滤；不传 / 空白 → 聚合四路。
     * IDOR 防护：只返回 {@code userId} 自己的选题（{@link TopicService#list(long, String)} 的 user_id 过滤）。
     */
    @GetMapping
    public ApiResponse<List<Topic>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(name = "source", required = false) String source) {
        return ApiResponse.ok(topicService.list(userId, source));
    }

    /** 选题详情。跨用户访问返回 PARAM_INVALID（不泄露存在性）。 */
    @GetMapping("/{id}")
    public ApiResponse<Topic> get(@AuthenticationPrincipal Long userId, @PathVariable long id) {
        return ApiResponse.ok(topicService.get(userId, id));
    }

    /** 新建选题。title 过 UGC 内容安全；source 缺省 faq。 */
    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal Long userId, @RequestBody CreateTopicRequest req) {
        long id = topicService.create(userId, req.title(), req.rationale(), req.source());
        return ApiResponse.ok(id);
    }

    /** 新建请求体。source 可缺省（→ faq）。 */
    public record CreateTopicRequest(String title, String rationale, String source) {}
}
