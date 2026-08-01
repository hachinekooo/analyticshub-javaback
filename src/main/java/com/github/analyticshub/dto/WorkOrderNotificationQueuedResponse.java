package com.github.analyticshub.dto;

public record WorkOrderNotificationQueuedResponse(
        String requestId,
        String notificationId,
        String status
) {
}
