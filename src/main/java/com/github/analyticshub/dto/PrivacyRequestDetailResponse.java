package com.github.analyticshub.dto;

public record PrivacyRequestDetailResponse(
        String requestId,
        String projectId,
        String userId,
        String deviceId,
        String requestType,
        String processor,
        String source,
        String status,
        String contactEmail,
        String requesterNote,
        String operator,
        String operatorNote,
        Object resultPayload,
        Object metadata,
        String requestedAt,
        String processedAt,
        String closedAt,
        String updatedAt
) {
}
