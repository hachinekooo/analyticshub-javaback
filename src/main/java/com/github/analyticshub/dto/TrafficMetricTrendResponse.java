package com.github.analyticshub.dto;

import java.util.List;

public record TrafficMetricTrendResponse(
        String projectId,
        String granularity,
        String rangeStart,
        String rangeEnd,
        List<TrafficMetricTrendPoint> points
) {}
