package com.github.analyticshub.service;

import com.github.analyticshub.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 修改语义映射前，统一保护仍由 active metric 或 Dashboard 使用的语义 Key。 */
@Service
public class AnalyticsSemanticDependencyService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsSemanticDependencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireUnusedByActiveAnalytics(String projectId, String semanticKey) {
        List<String> metricKeys = jdbcTemplate.queryForList("""
                SELECT metric_key
                  FROM analytics_metric_definitions
                 WHERE project_id = ? AND is_active = TRUE
                   AND (
                       definition ->> 'semanticEvent' = ?
                       OR definition ->> 'cohortEvent' = ?
                       OR definition ->> 'returnEvent' = ?
                       OR EXISTS (
                           SELECT 1
                             FROM jsonb_array_elements_text(
                                      CASE WHEN jsonb_typeof(definition -> 'steps') = 'array'
                                           THEN definition -> 'steps' ELSE '[]'::jsonb END
                                  ) AS step(value)
                            WHERE step.value = ?
                       )
                   )
                 ORDER BY metric_key
                """, String.class, projectId, semanticKey, semanticKey, semanticKey, semanticKey);
        List<String> dashboardKeys = jdbcTemplate.queryForList("""
                SELECT dashboard_key
                  FROM analytics_dashboards
                 WHERE project_id = ? AND is_active = TRUE
                   AND EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements(
                                  CASE WHEN jsonb_typeof(definition -> 'widgets') = 'array'
                                       THEN definition -> 'widgets' ELSE '[]'::jsonb END
                              ) AS widget(value)
                        WHERE (
                            widget.value ->> 'type' = 'core.productFunnel'
                            AND EXISTS (
                                SELECT 1
                                  FROM jsonb_array_elements_text(
                                           CASE WHEN jsonb_typeof(widget.value -> 'config' -> 'steps') = 'array'
                                                THEN widget.value -> 'config' -> 'steps' ELSE '[]'::jsonb END
                                       ) AS step(value)
                                 WHERE step.value = ?
                            )
                        ) OR (
                            widget.value ->> 'type' = 'core.retention'
                            AND (
                                widget.value -> 'config' ->> 'cohortEvent' = ?
                                OR widget.value -> 'config' ->> 'returnEvent' = ?
                            )
                        )
                   )
                 ORDER BY dashboard_key
                """, String.class, projectId, semanticKey, semanticKey, semanticKey);
        if (!metricKeys.isEmpty() || !dashboardKeys.isEmpty()) {
            throw new BusinessException(
                    "SEMANTIC_DEFINITION_IN_USE",
                    "语义定义仍被启用中的指标或 Dashboard 使用",
                    HttpStatus.CONFLICT,
                    Map.of("metricKeys", metricKeys, "dashboardKeys", dashboardKeys)
            );
        }
    }
}
