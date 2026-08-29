package com.github.analyticshub.dto;

import java.util.List;
import java.util.Map;

public record AnalyticsDataQualityResponse(
        String projectId,
        String from,
        String to,
        long totalEvents,
        boolean trustedSchemaPolicyConfigured,
        String schemaVersionPropertyKey,
        Map<String, Long> schemaVersions,
        boolean schemaVersionDistributionTruncated,
        List<AnalyticsDataQualityIssue> issues,
        List<AnalyticsPropertyQuality> propertyCoverage,
        int propertyCoverageTotal,
        boolean propertyCoverageTruncated
) {}
