package com.github.analyticshub.dto;

public record AdminFunnelStepResult(
        int stepIndex,
        String eventType,
        long users,
        double conversionRate,
        double dropOffRate
) {}
