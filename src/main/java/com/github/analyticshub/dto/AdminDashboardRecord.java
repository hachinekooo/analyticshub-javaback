package com.github.analyticshub.dto;

import java.time.Instant;
import java.util.Map;

public record AdminDashboardRecord(
        String projectId,
        String dashboardKey,
        Map<String, String> displayName,
        String description,
        int schemaVersion,
        Map<String, Object> definition,
        long revision,
        boolean isDefault,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
}
