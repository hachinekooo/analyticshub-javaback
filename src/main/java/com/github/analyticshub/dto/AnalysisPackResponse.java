package com.github.analyticshub.dto;

import java.time.Instant;
import java.util.Map;

public record AnalysisPackResponse(
        String projectId,
        String packKey,
        int packVersion,
        Map<String, String> displayName,
        String checksumSha256,
        boolean active,
        int propertyDefinitionsApplied,
        int metricDefinitionsApplied,
        Instant updatedAt
) {}
