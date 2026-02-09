package com.github.analyticshub.dto;

public record TrafficMetricSummaryResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        long pageViews,
        long visitors
) {}
