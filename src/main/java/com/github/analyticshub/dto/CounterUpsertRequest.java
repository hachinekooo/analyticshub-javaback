package com.github.analyticshub.dto;

public record CounterUpsertRequest(
        Long value,
        String displayName,
        String unit,
        Boolean isPublic,
        String description
) {}
