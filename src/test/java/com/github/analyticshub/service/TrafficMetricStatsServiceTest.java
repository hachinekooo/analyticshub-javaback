package com.github.analyticshub.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficMetricStatsServiceTest {

    @Test
    void summaryRangeDefaultsToAllTimeWhenNoBoundsProvided() {
        Instant before = Instant.now();

        AdminQueryUtils.Range range = TrafficMetricStatsService.resolveSummaryRange(null, null);

        assertEquals(Instant.EPOCH, range.start());
        assertTrue(!range.end().isBefore(before));
        assertTrue(!range.end().isAfter(Instant.now().plus(Duration.ofSeconds(1))));
    }

    @Test
    void summaryRangeUsesExplicitBoundsWhenProvided() {
        AdminQueryUtils.Range range = TrafficMetricStatsService.resolveSummaryRange("2026-01-01", "2026-01-02");

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), range.start());
        assertEquals(Instant.parse("2026-01-03T00:00:00Z"), range.end());
    }
}
