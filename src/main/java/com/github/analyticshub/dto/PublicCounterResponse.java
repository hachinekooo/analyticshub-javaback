package com.github.analyticshub.dto;

public record PublicCounterResponse(
        String key,
        long value,
        String displayName,
        String unit,
        String updatedAt
) {}
