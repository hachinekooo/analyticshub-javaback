package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

public record AnalyticsMetricResultResponse(
        String projectId,
        String metricKey,
        AnalyticsMetricType metricType,
        String from,
        String to,
        AnalyticsMetricResultClassification resultClassification,
        String diagnosticReason,
        JsonNode result
) {}
