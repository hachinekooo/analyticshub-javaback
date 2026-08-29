package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricResultClassification;
import com.github.analyticshub.dto.AnalyticsMetricResultResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.time.Duration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按已验证的 metricKey 计算通用指标，Dashboard 不再自行拼事件名和 SQL。 */
@Service
public class AnalyticsMetricEvaluationService {

    private final AnalysisConfigurationService configurationService;
    private final AdminProductAnalyticsService productAnalyticsService;
    private final AnalyticsPropertyFilterService propertyFilterService;
    private final SemanticDictionaryService semanticDictionaryService;
    private final ActorIdentityResolver actorIdentityResolver;
    private final MultiDataSourceManager dataSourceManager;
    private final AnalyticsQueryProperties queryProperties;
    private final ProjectTransactionExecutor projectTransactions;
    private final ObjectMapper objectMapper;

    public AnalyticsMetricEvaluationService(
            AnalysisConfigurationService configurationService,
            AdminProductAnalyticsService productAnalyticsService,
            AnalyticsPropertyFilterService propertyFilterService,
            SemanticDictionaryService semanticDictionaryService,
            ActorIdentityResolver actorIdentityResolver,
            MultiDataSourceManager dataSourceManager,
            AnalyticsQueryProperties queryProperties,
            ProjectTransactionExecutor projectTransactions,
            ObjectMapper objectMapper
    ) {
        this.configurationService = configurationService;
        this.productAnalyticsService = productAnalyticsService;
        this.propertyFilterService = propertyFilterService;
        this.semanticDictionaryService = semanticDictionaryService;
        this.actorIdentityResolver = actorIdentityResolver;
        this.dataSourceManager = dataSourceManager;
        this.queryProperties = queryProperties;
        this.projectTransactions = projectTransactions;
        this.objectMapper = objectMapper;
    }

    public AnalyticsMetricResultResponse evaluate(
            String projectId,
            String metricKey,
            String from,
            String to
    ) {
        AnalyticsMetricDefinitionResponse metric = configurationService.getMetric(projectId, metricKey);
        if (!metric.active()) {
            throw new BusinessException("ANALYTICS_METRIC_INACTIVE", "指标当前未启用");
        }
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        if (Duration.between(range.start(), range.end())
                .compareTo(Duration.ofDays(queryProperties.getMaxRangeDays())) > 0) {
            throw BusinessException.analyticsQueryRangeExceeded(queryProperties.getMaxRangeDays());
        }
        JsonNode result = switch (metric.metricType()) {
            case EVENT_COUNT -> evaluateEventAggregate(projectId, metric.definition(), range, false);
            case UNIQUE_ACTORS -> evaluateEventAggregate(projectId, metric.definition(), range, true);
            case FUNNEL_CONVERSION -> objectMapper.valueToTree(productAnalyticsService.getFunnel(
                    projectId,
                    range.start().toString(),
                    range.end().toString(),
                    requiredTextList(metric.definition(), "steps", 2, 12),
                    optionalText(metric.definition(), "groupBy"),
                    optionalText(metric.definition(), "journeyKey"),
                    serializedFilters(metric.definition())
            ));
            case RETENTION -> objectMapper.valueToTree(productAnalyticsService.getRetention(
                    projectId,
                    range.start().toString(),
                    range.end().toString(),
                    requiredText(metric.definition(), "cohortEvent"),
                    requiredText(metric.definition(), "returnEvent"),
                    optionalDays(metric.definition()),
                    serializedFilters(metric.definition())
            ));
        };
        boolean crossVersionDiagnostic = "CROSS_VERSION_VERIFIED".equals(
                optionalText(metric.definition(), "schemaScope")
        );
        boolean trustedSchemaConfigured = configurationService.getTrustedSchemaPolicy(projectId) != null;
        AnalyticsMetricResultClassification classification = crossVersionDiagnostic
                ? AnalyticsMetricResultClassification.CROSS_VERSION_DIAGNOSTIC
                : trustedSchemaConfigured
                    ? AnalyticsMetricResultClassification.TRUSTED_SCHEMA
                    : AnalyticsMetricResultClassification.UNGOVERNED_DIAGNOSTIC;
        return new AnalyticsMetricResultResponse(
                projectId, metric.metricKey(), metric.metricType(),
                range.start().toString(), range.end().toString(),
                classification,
                crossVersionDiagnostic ? optionalText(metric.definition(), "schemaScopeReason") : null,
                result
        );
    }

    private JsonNode evaluateEventAggregate(
            String projectId,
            JsonNode definition,
            AdminQueryUtils.Range range,
            boolean uniqueActors
    ) {
        MultiDataSourceManager.ProjectConfig config = dataSourceManager.getProjectConfig(projectId);
        if (config == null || !Boolean.TRUE.equals(config.isActive())) {
            throw BusinessException.projectInactive();
        }
        String semanticEvent = requiredText(definition, "semanticEvent");
        List<String> aliases = semanticDictionaryService.resolveActiveEventAliases(
                projectId, List.of(semanticEvent)
        ).getOrDefault(semanticEvent, List.of());
        AnalyticsPropertyFilterService.CompiledPropertyFilters filters = propertyFilterService.compile(
                projectId, serializedFilters(definition), "properties"
        );
        if (aliases.isEmpty()) {
            return numericResult(uniqueActors ? "actors" : "occurrences", 0L);
        }
        DataSource dataSource = dataSourceManager.getDataSource(projectId);
        String eventsTable = dataSourceManager.getTableName(projectId, "events");
        String linksTable = dataSourceManager.getTableName(projectId, "actor_identity_links");
        return executeMetricQuery(dataSource, jdbc -> {
            String placeholders = String.join(",", aliases.stream().map(ignored -> "?").toList());
            List<Object> arguments = new ArrayList<>();
            arguments.add(projectId);
            arguments.add(range.start().toEpochMilli());
            arguments.add(range.end().toEpochMilli());
            arguments.addAll(aliases);
            arguments.addAll(filters.arguments());
            String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
            enforceMatchingEventBudget(
                    jdbc, eventsTable, placeholders, filterSql, arguments
            );
            if (!uniqueActors) {
                Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + eventsTable
                                + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                                + " AND event_type IN (" + placeholders + ")" + filterSql,
                        Long.class,
                        arguments.toArray()
                );
                return numericResult("occurrences", count == null ? 0L : count);
            }
            arguments.add(queryProperties.getMaxCandidateRows() + 1);
            List<String> actors = jdbc.query(
                    "SELECT DISTINCT COALESCE(NULLIF(BTRIM(user_id), ''), device_id::text) AS actor_id FROM "
                            + eventsTable
                            + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                            + " AND event_type IN (" + placeholders + ")" + filterSql + " LIMIT ?",
                    (rs, rowNum) -> rs.getString("actor_id"),
                    arguments.toArray()
            );
            if (actors.size() > queryProperties.getMaxCandidateRows()) {
                throw BusinessException.analyticsQueryBudgetExceeded(queryProperties.getMaxCandidateRows());
            }
            Map<String, String> canonical = actorIdentityResolver.resolveCanonicalActors(
                    jdbc, linksTable, projectId, actors
            );
            long count = canonical.values().stream().distinct().count();
            return numericResult("actors", count);
        });
    }

    private void enforceMatchingEventBudget(
            JdbcTemplate jdbc,
            String eventsTable,
            String placeholders,
            String filterSql,
            List<Object> arguments
    ) {
        List<Object> budgetArguments = new ArrayList<>(arguments);
        budgetArguments.add(queryProperties.getMaxCandidateRows() + 1);
        Long candidateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + " AND event_type IN (" + placeholders + ")" + filterSql
                        + " LIMIT ?) bounded_candidates",
                Long.class,
                budgetArguments.toArray()
        );
        if (candidateCount != null && candidateCount > queryProperties.getMaxCandidateRows()) {
            throw BusinessException.analyticsQueryBudgetExceeded(queryProperties.getMaxCandidateRows());
        }
    }

    private <T> T executeMetricQuery(
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

    private ObjectNode numericResult(String field, long value) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put(field, value);
        return result;
    }

    private String serializedFilters(JsonNode definition) {
        JsonNode filters = definition.get("propertyFilters");
        return filters == null || filters.isNull() ? null : filters.toString();
    }

    private static String requiredText(JsonNode definition, String field) {
        JsonNode value = definition.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new BusinessException("INVALID_ANALYTICS_METRIC", field + " 缺失或格式无效");
        }
        return value.asString();
    }

    private static String optionalText(JsonNode definition, String field) {
        JsonNode value = definition.get(field);
        return value == null || value.isNull() ? null : requiredText(definition, field);
    }

    private static String requiredTextList(JsonNode definition, String field, int min, int max) {
        JsonNode values = definition.get(field);
        if (values == null || !values.isArray() || values.size() < min || values.size() > max) {
            throw new BusinessException("INVALID_ANALYTICS_METRIC", field + " 数量无效");
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.isString() || value.asString().isBlank()) {
                throw new BusinessException("INVALID_ANALYTICS_METRIC", field + " 包含无效值");
            }
            result.add(value.asString());
        });
        return String.join(",", result);
    }

    private static String optionalDays(JsonNode definition) {
        JsonNode values = definition.get("days");
        if (values == null || values.isNull()) return null;
        if (!values.isArray()) throw new BusinessException("INVALID_ANALYTICS_METRIC", "days 必须是数组");
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(Integer.toString(value.asInt(-1))));
        return String.join(",", result);
    }
}
