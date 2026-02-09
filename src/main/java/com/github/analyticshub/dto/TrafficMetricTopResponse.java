package com.github.analyticshub.dto;

import java.util.List;

public record TrafficMetricTopResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        List<TrafficMetricTopItem> items
) {}
