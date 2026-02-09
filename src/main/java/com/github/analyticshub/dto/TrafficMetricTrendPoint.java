package com.github.analyticshub.dto;

public record TrafficMetricTrendPoint(
        String time,
        long pageViews,
        long visitors
) {}
