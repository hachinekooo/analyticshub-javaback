package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

public record CounterRecord(
        String key,
        long value,
        JsonNode displayName,
        JsonNode unit,
        JsonNode eventTrigger,
        boolean isPublic,
        String description,
        String updatedAt,
        String lastRebuiltAt,
        Long lastRebuildEventCount
) {
    public CounterRecord(
            String key,
            long value,
            JsonNode displayName,
            JsonNode unit,
            JsonNode eventTrigger,
            boolean isPublic,
            String description,
            String updatedAt
    ) {
        this(key, value, displayName, unit, eventTrigger, isPublic, description, updatedAt, null, null);
    }
}
