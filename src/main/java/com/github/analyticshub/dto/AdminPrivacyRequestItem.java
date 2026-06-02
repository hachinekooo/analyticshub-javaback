package com.github.analyticshub.dto;

public record AdminPrivacyRequestItem(
        String requestId,
        String userId,
        String deviceId,
        String requestType,
        String processor,
        String status,
        String contactEmail,
        String requestedAt,
        String processedAt,
        String closedAt,
        String operator
) {
}
