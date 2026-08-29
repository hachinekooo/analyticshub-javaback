package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 运营总览数据
 */
public record AdminMetricsOverviewResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        long devicesInventoryTotal,
        long devicesActive,
        long usersActive,
        long cloudAccountsCreated,
        long cloudAccountsRecreated,
        long sessionsTotal,
        long eventsTotal,
        long avgSessionDurationMs,
        double avgEventsPerSession,
        List<String> availableMetricKeys
) {}
