package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 运营趋势响应
 */
public record AdminMetricsTrendResponse(
        String projectId,
        String granularity,
        String rangeStart,
        String rangeEnd,
        List<AdminMetricsTrendPoint> points
) {}
