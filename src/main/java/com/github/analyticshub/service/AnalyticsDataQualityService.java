package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AnalyticsDataQualityIssue;
import com.github.analyticshub.dto.AnalyticsDataQualityResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyQuality;
import com.github.analyticshub.dto.TrustedSchemaPolicyResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对选定时间窗内的原始事件做通用质量体检，不写回或修饰事实。 */
@Service
public class AnalyticsDataQualityService {

    private static final int MAX_PROPERTY_COVERAGE_ITEMS = 50;
    private static final int MAX_DISTRIBUTION_VALUES = 200;

    private final MultiDataSourceManager dataSourceManager;
    private final AnalyticsPropertyDefinitionService propertyDefinitionService;
    private final AnalysisConfigurationService analysisConfigurationService;
    private final AnalyticsQueryProperties queryProperties;
    private final ProjectTransactionExecutor projectTransactions;
    private final ObjectMapper objectMapper;

    public AnalyticsDataQualityService(
            MultiDataSourceManager dataSourceManager,
            AnalyticsPropertyDefinitionService propertyDefinitionService,
            AnalysisConfigurationService analysisConfigurationService,
            AnalyticsQueryProperties queryProperties,
            ProjectTransactionExecutor projectTransactions,
            ObjectMapper objectMapper
    ) {
        this.dataSourceManager = dataSourceManager;
        this.propertyDefinitionService = propertyDefinitionService;
        this.analysisConfigurationService = analysisConfigurationService;
        this.queryProperties = queryProperties;
        this.projectTransactions = projectTransactions;
        this.objectMapper = objectMapper;
    }

    public AnalyticsDataQualityResponse inspect(String projectId, String from, String to) {
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        if (Duration.between(range.start(), range.end())
                .compareTo(Duration.ofDays(queryProperties.getMaxRangeDays())) > 0) {
            throw BusinessException.analyticsQueryRangeExceeded(queryProperties.getMaxRangeDays());
        }
        MultiDataSourceManager.ProjectConfig config = dataSourceManager.getProjectConfig(projectId);
        if (config == null) throw BusinessException.invalidProject(projectId);
        if (!Boolean.TRUE.equals(config.isActive())) throw BusinessException.projectInactive();
        DataSource dataSource = dataSourceManager.getDataSource(projectId);
        String eventsTable = dataSourceManager.getTableName(projectId, "events");
        List<AnalyticsPropertyDefinitionResponse> governedDefinitions = propertyDefinitionService.list(projectId).items()
                .stream()
                .filter(item -> item.active() && !item.sensitive()
                        && (item.filterable() || item.groupable() || item.journeyKey()
                        || (item.allowedValues() != null && !item.allowedValues().isEmpty())))
                .toList();
        List<AnalyticsPropertyDefinitionResponse> inspectedDefinitions = governedDefinitions.stream()
                .limit(MAX_PROPERTY_COVERAGE_ITEMS)
                .toList();
        var trustedSchemaPolicy = analysisConfigurationService.getTrustedSchemaPolicy(projectId);
        String schemaVersionPropertyKey = trustedSchemaPolicy == null
                ? null
                : trustedSchemaPolicy.propertyKey();

        return executeInspection(dataSource, jdbc -> {
            long start = range.start().toEpochMilli();
            long end = range.end().toEpochMilli();
            long total = count(jdbc, "SELECT COUNT(*) FROM (SELECT 1 FROM " + eventsTable
                            + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                            + " LIMIT ?) bounded_quality_candidates",
                    projectId, start, end, queryProperties.getMaxDataQualityRows() + 1);
            if (total > queryProperties.getMaxDataQualityRows()) {
                throw BusinessException.analyticsQueryBudgetExceeded(
                        queryProperties.getMaxDataQualityRows()
                );
            }
            SchemaVersionInspection schemaInspection = trustedSchemaPolicy == null
                    ? SchemaVersionInspection.empty()
                    : inspectSchemaVersions(
                            jdbc, eventsTable, projectId, start, end, trustedSchemaPolicy
                    );
            List<AnalyticsDataQualityIssue> issues = new ArrayList<>();
            if (schemaVersionPropertyKey != null) {
                addIssue(issues, "missing_schema_version", "warning",
                        schemaInspection.missingEvents(),
                        "事件缺少项目可信协议属性，不能进入稳定口径");
                addIssue(issues, "untrusted_schema_value", "warning", schemaInspection.untrustedEvents(),
                        "事件协议值不在项目可信基线中，不能进入稳定口径");
                addIssue(issues, "schema_version_distribution_truncated", "warning",
                        schemaInspection.omittedDistinctValues(),
                        "协议值种类超过单次展示上限，分布仅展示高频项；异常事件总数仍按完整范围计算");
            }
            addIssue(issues, "oversized_properties", "warning", count(jdbc,
                    "SELECT COUNT(*) FROM " + eventsTable
                            + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                            + " AND COALESCE(properties_size_bytes, octet_length(properties::text)) > ?",
                    projectId, start, end, EventService.MAX_PROPERTIES_BYTES),
                    "历史事件属性超过当前采集预算");
            addIssue(issues, "future_event_timestamp", "error", count(jdbc,
                    "SELECT COUNT(*) FROM " + eventsTable
                            + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                            + " AND event_timestamp > EXTRACT(EPOCH FROM created_at + INTERVAL '24 hours') * 1000",
                    projectId, start, end), "客户端事件时间比接收时间超前超过 24 小时");
            addIssue(issues, "stale_event_timestamp", "warning", count(jdbc,
                    "SELECT COUNT(*) FROM " + eventsTable
                            + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                            + " AND event_timestamp < EXTRACT(EPOCH FROM created_at - INTERVAL '366 days') * 1000",
                    projectId, start, end), "客户端事件时间比接收时间早超过 366 天");

            List<AnalyticsPropertyQuality> coverage = propertyCoverage(
                    jdbc, eventsTable, projectId, start, end, inspectedDefinitions
            );
            int omittedDefinitions = governedDefinitions.size() - inspectedDefinitions.size();
            addPropertyCoverageIssues(issues, coverage, omittedDefinitions);

            return new AnalyticsDataQualityResponse(
                    projectId, range.start().toString(), range.end().toString(), total,
                    trustedSchemaPolicy != null,
                    schemaVersionPropertyKey,
                    schemaInspection.distribution(),
                    schemaInspection.omittedDistinctValues() > 0,
                    List.copyOf(issues), coverage, governedDefinitions.size(), omittedDefinitions > 0
            );
        });
    }

    private static SchemaVersionInspection inspectSchemaVersions(
            JdbcTemplate jdbc,
            String table,
            String projectId,
            long start,
            long end,
            TrustedSchemaPolicyResponse policy
    ) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT COALESCE(NULLIF(BTRIM(properties ->> ?), ''), '(missing)') AS value, COUNT(*) AS total"
                        + " FROM " + table
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + " GROUP BY value ORDER BY total DESC, value LIMIT ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getString("value"), rs.getLong("total")),
                policy.propertyKey(), projectId, start, end, MAX_DISTRIBUTION_VALUES
        );

        String trustedPlaceholders = String.join(",", java.util.Collections.nCopies(
                policy.trustedValues().size(), "?"
        ));
        List<Object> arguments = new ArrayList<>();
        arguments.add(policy.propertyKey());
        arguments.add(projectId);
        arguments.add(start);
        arguments.add(end);
        arguments.addAll(policy.trustedValues());
        SchemaVersionCounts counts = jdbc.queryForObject("""
                WITH scoped AS (
                    SELECT NULLIF(BTRIM(properties ->> ?), '') AS normalized_value
                      FROM %s
                     WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?
                )
                SELECT COUNT(*) FILTER (WHERE normalized_value IS NULL) AS missing_events,
                       COUNT(*) FILTER (
                           WHERE normalized_value IS NOT NULL
                             AND normalized_value NOT IN (%s)
                       ) AS untrusted_events,
                       COUNT(DISTINCT COALESCE(normalized_value, '(missing)')) AS distinct_values
                  FROM scoped
                """.formatted(table, trustedPlaceholders), (rs, rowNum) -> new SchemaVersionCounts(
                rs.getLong("missing_events"),
                rs.getLong("untrusted_events"),
                rs.getInt("distinct_values")
        ), arguments.toArray());
        if (counts == null) {
            counts = new SchemaVersionCounts(0, 0, 0);
        }
        return new SchemaVersionInspection(
                Collections.unmodifiableMap(new LinkedHashMap<>(result)),
                counts.missingEvents(),
                counts.untrustedEvents(),
                Math.max(0, counts.distinctValues() - result.size())
        );
    }

    private <T> T executeInspection(
            DataSource dataSource,
            java.util.function.Function<JdbcTemplate, T> operation
    ) {
        try {
            return projectTransactions.executeReadOnly(
                    dataSource,
                    queryProperties.getTimeoutSeconds(),
                    operation
            );
        } catch (QueryTimeoutException | TransactionTimedOutException exception) {
            throw BusinessException.analyticsQueryTimedOut();
        } catch (DataAccessException exception) {
            if (hasSqlState(exception, "57014")) {
                throw BusinessException.analyticsQueryTimedOut();
            }
            throw exception;
        }
    }

    private static boolean hasSqlState(Throwable error, String expectedState) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && expectedState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<AnalyticsPropertyQuality> propertyCoverage(
            JdbcTemplate jdbc,
            String table,
            String projectId,
            long start,
            long end,
            List<AnalyticsPropertyDefinitionResponse> definitions
    ) {
        if (definitions.isEmpty()) return List.of();
        String values = String.join(",", definitions.stream()
                .map(ignored -> "(?, ?, ?, CAST(? AS jsonb))").toList());
        List<Object> arguments = new ArrayList<>();
        for (AnalyticsPropertyDefinitionResponse definition : definitions) {
            arguments.add(definition.propertyKey());
            arguments.add(jsonType(definition.dataType()));
            arguments.add(definition.dataType().name());
            arguments.add(definition.allowedValues() == null || definition.allowedValues().isEmpty()
                    ? null
                    : toJson(definition.allowedValues()));
        }
        arguments.add(projectId);
        arguments.add(start);
        arguments.add(end);
        return jdbc.query("""
                WITH definitions(property_key, expected_type, data_type, allowed_values) AS (VALUES %s),
                scoped_events AS (
                    SELECT properties FROM %s
                     WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?
                )
                SELECT d.property_key,
                       COUNT(*) FILTER (WHERE jsonb_exists(e.properties, d.property_key)) AS present_events,
                       COUNT(*) FILTER (
                           WHERE jsonb_exists(e.properties, d.property_key)
                             AND (
                                 jsonb_typeof(e.properties -> d.property_key) <> d.expected_type
                                 OR (d.data_type = 'INTEGER'
                                     AND jsonb_typeof(e.properties -> d.property_key) = 'number'
                                     AND (e.properties ->> d.property_key)::numeric
                                         <> trunc((e.properties ->> d.property_key)::numeric))
                             )
                       ) AS mismatch_events,
                       COUNT(*) FILTER (
                           WHERE jsonb_exists(e.properties, d.property_key)
                             AND jsonb_typeof(e.properties -> d.property_key) = d.expected_type
                             AND (d.data_type <> 'INTEGER'
                                  OR (e.properties ->> d.property_key)::numeric
                                     = trunc((e.properties ->> d.property_key)::numeric))
                             AND d.allowed_values IS NOT NULL
                             AND NOT EXISTS (
                                 SELECT 1
                                   FROM jsonb_array_elements_text(d.allowed_values) AS allowed(value)
                                  WHERE CASE d.data_type
                                      WHEN 'NUMBER' THEN (e.properties ->> d.property_key)::numeric
                                                          = allowed.value::numeric
                                      WHEN 'INTEGER' THEN (e.properties ->> d.property_key)::numeric
                                                           = allowed.value::numeric
                                      WHEN 'BOOLEAN' THEN lower(e.properties ->> d.property_key)
                                                           = allowed.value
                                      ELSE btrim(e.properties ->> d.property_key, E' \\t\\n\\r\\f') = allowed.value
                                  END
                             )
                       ) AS disallowed_value_events
                  FROM definitions d LEFT JOIN scoped_events e ON TRUE
                 GROUP BY d.property_key
                 ORDER BY d.property_key
                """.formatted(values, table), (rs, rowNum) -> new AnalyticsPropertyQuality(
                rs.getString("property_key"),
                rs.getLong("present_events"),
                rs.getLong("mismatch_events"),
                rs.getLong("disallowed_value_events")
        ), arguments.toArray());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize analytics quality policy", exception);
        }
    }

    private static String jsonType(AnalyticsPropertyDataType type) {
        return switch (type) {
            case STRING -> "string";
            case BOOLEAN -> "boolean";
            case INTEGER, NUMBER -> "number";
        };
    }

    static void addPropertyCoverageIssues(
            List<AnalyticsDataQualityIssue> issues,
            List<AnalyticsPropertyQuality> coverage,
            int omittedDefinitions
    ) {
        long mismatchEvents = coverage.stream()
                .mapToLong(AnalyticsPropertyQuality::typeMismatchEvents)
                .sum();
        long disallowedValueEvents = coverage.stream()
                .mapToLong(AnalyticsPropertyQuality::disallowedValueEvents)
                .sum();
        addIssue(issues, "property_type_mismatch", "error", mismatchEvents,
                "受治理属性存在类型不一致事件，相关筛选、分组或旅程结果可能不完整");
        addIssue(issues, "property_value_outside_allowlist", "error", disallowedValueEvents,
                "受治理属性存在允许值域之外的事件，不能进入对应稳定指标口径");
        addIssue(issues, "property_coverage_truncated", "warning", omittedDefinitions,
                "受治理属性超过单次检查上限，以下覆盖结果不是完整清单");
    }

    private static void addIssue(
            List<AnalyticsDataQualityIssue> issues,
            String code,
            String severity,
            long count,
            String description
    ) {
        if (count > 0) issues.add(new AnalyticsDataQualityIssue(code, severity, count, description));
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... arguments) {
        Long result = jdbc.queryForObject(sql, Long.class, arguments);
        return result == null ? 0L : result;
    }

    private record SchemaVersionCounts(long missingEvents, long untrustedEvents, int distinctValues) {}

    private record SchemaVersionInspection(
            Map<String, Long> distribution,
            long missingEvents,
            long untrustedEvents,
            int omittedDistinctValues
    ) {
        private static SchemaVersionInspection empty() {
            return new SchemaVersionInspection(Map.of(), 0, 0, 0);
        }
    }
}
