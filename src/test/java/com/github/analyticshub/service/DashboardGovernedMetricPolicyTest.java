package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardGovernedMetricPolicyTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
    private final DashboardGovernedMetricPolicy policy = new DashboardGovernedMetricPolicy(configuration);

    @Test
    void validatesOnlyNewMetricReferences() throws Exception {
        var existing = objectMapper.readTree(dashboard("existing.metric"));
        var updated = objectMapper.readTree(dashboard("existing.metric", "new.metric"));
        when(configuration.getMetric("project", "new.metric")).thenReturn(metric("new.metric", true));

        assertThatCode(() -> policy.validateForWrite("project", updated, existing, false))
                .doesNotThrowAnyException();

        verify(configuration).getMetric("project", "new.metric");
        verify(configuration, never()).getMetric("project", "existing.metric");
    }

    @Test
    void rejectsNewReferencesThatAreMissingOrInactive() throws Exception {
        var missing = objectMapper.readTree(dashboard("missing.metric"));
        when(configuration.getMetric("project", "missing.metric"))
                .thenThrow(new BusinessException("ANALYTICS_METRIC_NOT_FOUND", "not found"));

        assertThatThrownBy(() -> policy.validateForWrite("project", missing, null, true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE")
                );

        var inactive = objectMapper.readTree(dashboard("inactive.metric"));
        when(configuration.getMetric("project", "inactive.metric"))
                .thenReturn(metric("inactive.metric", false));
        assertThatThrownBy(() -> policy.validateForWrite("project", inactive, null, true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE")
                );
    }

    @Test
    void revalidatesEveryReferenceWhenDashboardWillBecomeActive() throws Exception {
        var unchanged = objectMapper.readTree(dashboard("inactive.metric"));
        when(configuration.getMetric("project", "inactive.metric"))
                .thenReturn(metric("inactive.metric", false));

        assertThatThrownBy(() -> policy.validateForWrite("project", unchanged, unchanged, true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE")
                );
    }

    private AnalyticsMetricDefinitionResponse metric(String key, boolean active) {
        return new AnalyticsMetricDefinitionResponse(
                "project", key, Map.of("en", key), AnalyticsMetricType.EVENT_COUNT,
                objectMapper.createObjectNode(), null, active, Instant.EPOCH, Instant.EPOCH
        );
    }

    private static String dashboard(String... metricKeys) {
        StringBuilder widgets = new StringBuilder();
        for (int index = 0; index < metricKeys.length; index++) {
            if (index > 0) widgets.append(',');
            widgets.append("""
                    {"id":"metric-%d","type":"core.governedMetric","config":{"metricKey":"%s"}}
                    """.formatted(index, metricKeys[index]));
        }
        return "{\"widgets\":[" + widgets + "]}";
    }
}
