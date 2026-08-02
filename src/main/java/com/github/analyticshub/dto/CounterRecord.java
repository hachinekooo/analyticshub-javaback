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
        Long lastRebuildEventCount,
        long rebuildOffset,
        CounterHistoryMode historyMode,
        String eventCountStartAt
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
        this(key, value, displayName, unit, eventTrigger, isPublic, description, updatedAt,
                null, null, 0L, CounterHistoryMode.INCLUDE_EXISTING, null);
    }

    public CounterRecord(
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
        this(key, value, displayName, unit, eventTrigger, isPublic, description, updatedAt,
                lastRebuiltAt, lastRebuildEventCount, 0L, CounterHistoryMode.INCLUDE_EXISTING, null);
    }
}
