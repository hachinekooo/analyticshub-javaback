package com.github.analyticshub.dto;

public record AnalyticsDataQualityIssue(
        String code,
        String severity,
        long count,
        String description
) {}
