package com.github.analyticshub.dto;

public record WorkOrderActivityItem(
        String activityId,
        String activityType,
        String fromStatus,
        String toStatus,
        String actor,
        Object details,
        String createdAt
) {
}
