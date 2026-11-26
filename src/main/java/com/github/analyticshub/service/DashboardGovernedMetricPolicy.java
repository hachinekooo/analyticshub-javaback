package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/** 确保即将生效的 Dashboard 只引用真实存在且处于启用状态的治理指标。 */
@Component
@RequiredArgsConstructor
public class DashboardGovernedMetricPolicy {

    private final AnalysisConfigurationService configurationService;

    /**
     * 校验看板引用的已登记指标。
     *
     * @param definition 待写入的看板定义。
     * @param existingDefinition 已保存的定义；用于识别未启用草稿中哪些引用是新增的，新建时可为空。
     * @param targetActive 写入后的启用状态；启用时重查全部引用，停用草稿只查新增引用。
     */
    public void validateForWrite(
            String projectId,
            JsonNode definition,
            JsonNode existingDefinition,
            boolean targetActive
    ) {
        Set<MetricReference> metricsToValidate = collectMetricReferences(definition);
        if (!targetActive) {
            // 未启用的草稿只校验新增引用；重新启用时则必须重新验证全部引用。
            metricsToValidate.removeAll(collectMetricReferences(existingDefinition));
        }
        for (MetricReference reference : metricsToValidate) {
            String metricKey = reference.metricKey();
            AnalyticsMetricDefinitionResponse metric;
            try {
                metric = configurationService.getMetric(projectId, metricKey);
            } catch (BusinessException exception) {
                throw unavailable(metricKey);
            }
            if (!metric.active()) throw unavailable(metricKey);
            if (reference.responseSummary() && !supportsResponseSummary(metric.metricType(), metric.definition())) {
                throw new BusinessException("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE",
                        "回答概况需要未分组、按人数统计的两步漏斗: " + metricKey);
            }
            if (reference.trended() && metric.metricType() != AnalyticsMetricType.UNIQUE_ACTORS) {
                throw new BusinessException("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE",
                        "仅去重人数指标支持趋势展示: " + metricKey);
            }
        }
    }

    /** 看板或指标从任一端编辑时，都必须保留“展示人数 → 回答人数”的计算口径。 */
    static boolean supportsResponseSummary(AnalyticsMetricType type, JsonNode definition) {
        return type == AnalyticsMetricType.FUNNEL_CONVERSION
                && definition.path("steps").isArray() && definition.path("steps").size() == 2
                && !definition.hasNonNull("groupBy") && !definition.hasNonNull("journeyKey")
                && !definition.hasNonNull("firstEventMilestones");
    }

    private record MetricReference(String metricKey, boolean trended, boolean responseSummary) {}

    private static Set<MetricReference> collectMetricReferences(JsonNode definition) {
        Set<MetricReference> keys = new LinkedHashSet<>();
        JsonNode widgets = definition == null ? null : definition.get("widgets");
        if (widgets == null || !widgets.isArray()) return keys;
        for (JsonNode widget : widgets) {
            if (!"core.governedMetric".equals(widget.path("type").asString())) continue;
            String metricKey = widget.path("config").path("metricKey").asString();
            if (!metricKey.isBlank()) {
                keys.add(new MetricReference(metricKey, Set.of("day", "week").contains(
                        widget.path("config").path("interval").asString()), false));
            }
            String responseMetricKey = widget.path("config").path("responseMetricKey").asString();
            if (!responseMetricKey.isBlank()) keys.add(new MetricReference(responseMetricKey, false, true));
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
