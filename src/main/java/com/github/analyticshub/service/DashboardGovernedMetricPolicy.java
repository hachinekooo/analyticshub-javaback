package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/** 确保即将生效的 Dashboard 只引用真实存在且处于启用状态的治理指标。 */
@Component
public class DashboardGovernedMetricPolicy {

    private final AnalysisConfigurationService configurationService;

    public DashboardGovernedMetricPolicy(AnalysisConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public void validateForWrite(
            String projectId,
            JsonNode definition,
            JsonNode existingDefinition,
            boolean targetActive
    ) {
        Set<String> keysToValidate = collectMetricKeys(definition);
        if (!targetActive) {
            // 未启用的草稿只校验新增引用；重新启用时则必须重新验证全部引用。
            keysToValidate.removeAll(collectMetricKeys(existingDefinition));
        }
        for (String metricKey : keysToValidate) {
            AnalyticsMetricDefinitionResponse metric;
            try {
                metric = configurationService.getMetric(projectId, metricKey);
            } catch (BusinessException exception) {
                throw unavailable(metricKey);
            }
            if (!metric.active()) throw unavailable(metricKey);
        }
    }

    private static Set<String> collectMetricKeys(JsonNode definition) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonNode widgets = definition == null ? null : definition.get("widgets");
        if (widgets == null || !widgets.isArray()) return keys;
        for (JsonNode widget : widgets) {
            if (!"core.governedMetric".equals(widget.path("type").asString())) continue;
            String metricKey = widget.path("config").path("metricKey").asString();
            if (!metricKey.isBlank()) keys.add(metricKey);
        }
        return keys;
    }

    private static BusinessException unavailable(String metricKey) {
        return new BusinessException(
                "DASHBOARD_GOVERNED_METRIC_UNAVAILABLE",
                "Dashboard 指标不存在或未启用: " + metricKey
        );
    }
}
