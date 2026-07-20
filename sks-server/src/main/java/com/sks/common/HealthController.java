package com.sks.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小健康检查端点（nginx 反代 /api → sks-server:8080）。
 * 仅返回运行状态，不暴露内部信息。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
