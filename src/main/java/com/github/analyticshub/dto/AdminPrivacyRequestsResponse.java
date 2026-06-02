package com.github.analyticshub.dto;

import java.util.List;

public record AdminPrivacyRequestsResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminPrivacyRequestItem> items
) {
}
