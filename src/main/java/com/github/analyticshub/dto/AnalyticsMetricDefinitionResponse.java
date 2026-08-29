package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public record AnalyticsMetricDefinitionResponse(
        String projectId,
        String metricKey,
        Map<String, String> displayName,
        AnalyticsMetricType metricType,
        JsonNode definition,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
