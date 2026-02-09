package com.github.analyticshub.dto;

public record CounterRecord(
        String key,
        long value,
        String displayName,
        String unit,
        boolean isPublic,
        String description,
        String updatedAt
) {}
