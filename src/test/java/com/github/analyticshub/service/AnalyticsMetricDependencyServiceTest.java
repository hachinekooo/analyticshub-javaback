package com.github.analyticshub.service;

import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsMetricDependencyServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AnalyticsMetricDependencyService service = new AnalyticsMetricDependencyService(jdbcTemplate);

    @Test
    void skipsDatabaseWorkForAnEmptyDeactivationSet() {
        assertThatCode(() -> service.requireUnusedByActiveDashboards("project", Set.of()))
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void reportsEveryBlockingDashboardAndMetric() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("dashboard_key", "growth", "metric_key", "purchase.count"),
                Map.of("dashboard_key", "growth", "metric_key", "paywall.conversion"),
                Map.of("dashboard_key", "operations", "metric_key", "paywall.conversion")
        ));

        assertThatThrownBy(() -> service.requireUnusedByActiveDashboards(
                "project", Set.of("paywall.conversion", "purchase.count")
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("ANALYTICS_METRIC_IN_USE");
            assertThat(exception.getDetails()).isEqualTo(Map.of(
                    "metricKeys", List.of("purchase.count", "paywall.conversion"),
                    "dashboardKeys", List.of("growth", "operations")
            ));
        });
    }
}
