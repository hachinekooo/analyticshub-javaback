package com.github.analyticshub.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CounterUpsertRequest(
        Long value,
        JsonNode displayName,
        JsonNode unit,
        JsonNode eventTrigger,
        Boolean isPublic,
        String description
) {}
