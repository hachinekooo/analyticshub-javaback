package com.github.analyticshub.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 管理端 - 流量指标记录
 */
public record AdminTrafficMetricRecord(
        String metricId,
        String metricType,
        String pagePath,
        String referrer,
        Long metricTimestamp,
        String createdAt,
        String deviceId,
        String userId,
        String sessionId,
        JsonNode metadata
) {}
