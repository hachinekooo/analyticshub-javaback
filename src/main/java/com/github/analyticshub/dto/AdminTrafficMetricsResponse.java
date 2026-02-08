package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 流量指标列表响应
 */
public record AdminTrafficMetricsResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminTrafficMetricRecord> items
) {}
