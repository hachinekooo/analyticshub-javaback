package com.github.analyticshub.dto;

/**
 * 管理端 - 运营总览数据
 */
public record AdminMetricsOverviewResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        long devicesTotal,
        long devicesActive,
        long usersActive,
        long sessionsTotal,
        long eventsTotal,
        long avgSessionDurationMs,
        double avgEventsPerSession
) {}
