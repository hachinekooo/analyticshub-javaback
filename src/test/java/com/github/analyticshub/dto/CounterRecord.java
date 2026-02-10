package com.github.analyticshub.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CounterRecord(
        String key,
        long value,
        JsonNode displayName,
        JsonNode unit,
        JsonNode eventTrigger,
        boolean isPublic,
        String description,
        String updatedAt
) {}
