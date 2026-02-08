package com.github.analyticshub.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /**
     * 健康检查接口
     * GET /health
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        // Keep this lightweight: used by load balancers and local checks.
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "analyticshub-javaback");
        health.put("timestamp", Instant.now().toString());
        health.put("version", "1.0.0");
        
        return ResponseEntity.ok(health);
    }
}
