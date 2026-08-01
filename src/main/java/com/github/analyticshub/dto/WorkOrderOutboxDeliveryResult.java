package com.github.analyticshub.dto;

public record WorkOrderOutboxDeliveryResult(
        String projectId,
        int claimed,
        int sent,
        int retryScheduled,
        int dead
) {
}
