package com.github.analyticshub.service;

import com.github.analyticshub.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 停用治理指标前，保护仍由启用中 Dashboard 持有的稳定引用。 */
@Service
public class AnalyticsMetricDependencyService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsMetricDependencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireUnusedByActiveDashboards(String projectId, Set<String> metricKeys) {
        if (metricKeys == null || metricKeys.isEmpty()) return;
        List<String> orderedKeys = metricKeys.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(orderedKeys.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        arguments.addAll(orderedKeys);
        String sql = """
                SELECT DISTINCT dashboard_key,
                                widget.value -> 'config' ->> 'metricKey' AS metric_key
                  FROM analytics_dashboards
                  CROSS JOIN LATERAL jsonb_array_elements(
                      CASE WHEN jsonb_typeof(definition -> 'widgets') = 'array'
                           THEN definition -> 'widgets' ELSE '[]'::jsonb END
                  ) AS widget(value)
                 WHERE project_id = ? AND is_active = TRUE
                   AND widget.value ->> 'type' = 'core.governedMetric'
                   AND widget.value -> 'config' ->> 'metricKey' IN (%s)
                 ORDER BY dashboard_key, metric_key
                """.formatted(placeholders);
        List<Map<String, Object>> references = jdbcTemplate.queryForList(sql, arguments.toArray());
        if (references.isEmpty()) return;

        Set<String> blockingMetrics = new LinkedHashSet<>();
        Set<String> blockingDashboards = new LinkedHashSet<>();
        for (Map<String, Object> reference : references) {
            blockingMetrics.add(String.valueOf(reference.get("metric_key")));
            blockingDashboards.add(String.valueOf(reference.get("dashboard_key")));
        }
        throw new BusinessException(
                "ANALYTICS_METRIC_IN_USE",
                "治理指标仍被启用中的 Dashboard 使用",
                HttpStatus.CONFLICT,
                Map.of(
                        "metricKeys", List.copyOf(blockingMetrics),
                        "dashboardKeys", List.copyOf(blockingDashboards)
                )
        );
    }
}
