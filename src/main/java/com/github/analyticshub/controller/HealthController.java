package com.github.analyticshub.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
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

    private final String applicationVersion;

    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        this.applicationVersion = buildProperties == null ? "development" : buildProperties.getVersion();
    }

    /**
     * 供负载均衡和本地探活使用；不连接数据库，也不返回组件详情。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "analyticshub-javaback");
        health.put("timestamp", Instant.now().toString());
        health.put("version", applicationVersion);
        
        return ResponseEntity.ok(health);
    }
}
