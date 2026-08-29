package com.github.analyticshub.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalyticsPropertyDefinitionResponse(
        String projectId,
        String propertyKey,
        Map<String, String> displayName,
        AnalyticsPropertyDataType dataType,
        String description,
        List<String> allowedValues,
        boolean filterable,
        boolean groupable,
        boolean journeyKey,
        boolean sensitive,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
