package com.github.analyticshub.dto;

public record AdminRetentionBucket(
        int day,
        long retainedUsers,
        double retentionRate
) {}
