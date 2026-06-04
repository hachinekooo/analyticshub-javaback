package com.github.analyticshub.dto;

public record PrivacyRequestCreatedResponse(
        String requestId,
        String requestType,
        String processor,
        String status,
        String requestedAt,
        String contactEmail,
        String message,
        Object resultPayload
) {
}
