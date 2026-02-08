package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 事件排行响应
 */
public record AdminMetricsTopEventsResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        List<AdminMetricsTopEvent> items
) {}
