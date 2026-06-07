package com.github.analyticshub.dto;

import java.util.List;

public record AdminRetentionResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        String cohortEvent,
        String returnEvent,
        long cohortUsers,
        List<AdminRetentionBucket> buckets
) {}
