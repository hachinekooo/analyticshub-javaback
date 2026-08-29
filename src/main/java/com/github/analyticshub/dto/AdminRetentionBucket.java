package com.github.analyticshub.dto;

public record AdminRetentionBucket(
        int day,
        long eligibleUsers,
        long retainedUsers,
        double retentionRate
) {}
