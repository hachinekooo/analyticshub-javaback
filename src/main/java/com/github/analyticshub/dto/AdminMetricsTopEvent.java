package com.github.analyticshub.dto;

/**
 * 管理端 - 事件排行
 */
public record AdminMetricsTopEvent(
        String eventType,
        long count
) {}
