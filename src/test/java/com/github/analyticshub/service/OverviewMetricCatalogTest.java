package com.github.analyticshub.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverviewMetricCatalogTest {

    @Test
    void exposesSystemMetricsWithoutAssumingOptionalBusinessCapabilities() {
        assertThat(OverviewMetricCatalog.availableOverviewKeys(Map.of()))
                .containsExactly(
                        OverviewMetricCatalog.ACTIVE_DEVICES,
                        OverviewMetricCatalog.ACTIVE_ACTORS,
                        OverviewMetricCatalog.EVENT_OCCURRENCES,
                        OverviewMetricCatalog.TOP_ACTIVE_APP_VERSION
                );
        assertThat(OverviewMetricCatalog.availableTrendKeys(Map.of()))
                .containsExactly(
                        OverviewMetricCatalog.ACTIVE_ACTORS,
                        OverviewMetricCatalog.ACTIVE_DEVICES
                );
    }

    @Test
    void exposesMappedBusinessMetricsEvenWhenTheirCurrentPeriodValueIsZero() {
        Map<String, List<String>> aliases = Map.of(
                OverviewMetricCatalog.ACCOUNT_CREATED, List.of("cloud_account_created"),
                OverviewMetricCatalog.ACCOUNT_RECREATED, List.of()
        );

        assertThat(OverviewMetricCatalog.availableOverviewKeys(aliases))
                .contains(OverviewMetricCatalog.ACCOUNT_CREATED)
                .doesNotContain(OverviewMetricCatalog.ACCOUNT_RECREATED);
    }
}
