package com.github.analyticshub.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record TrafficMetricTrackRequest(
        String metricType,
        String pagePath,
        String referrer,
        Long timestamp,
        UUID sessionId,
        JsonNode metadata
) {}
