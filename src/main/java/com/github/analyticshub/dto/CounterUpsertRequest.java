package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

public record CounterUpsertRequest(
        Long value,
        JsonNode displayName,
        JsonNode unit,
        JsonNode eventTrigger,
        Boolean clearEventTrigger,
        Boolean isPublic,
        String description
) {
    public CounterUpsertRequest(
            Long value,
            JsonNode displayName,
            JsonNode unit,
            JsonNode eventTrigger,
            Boolean isPublic,
            String description
    ) {
        this(value, displayName, unit, eventTrigger, false, isPublic, description);
    }
}
