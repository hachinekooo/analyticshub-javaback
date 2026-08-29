package com.github.analyticshub.dto;

public record AnalyticsPropertyQuality(
        String propertyKey,
        long presentEvents,
        long typeMismatchEvents,
        long disallowedValueEvents
) {}
