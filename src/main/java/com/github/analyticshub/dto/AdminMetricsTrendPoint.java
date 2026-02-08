package com.github.analyticshub.dto;

/**
 * 管理端 - 运营趋势数据点
 */
public record AdminMetricsTrendPoint(
        String time,
        long events,
        long sessions
) {}
