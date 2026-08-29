package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricResultClassification;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsMetricEvaluationServiceTest {

    @Test
    void crossVersionMetricResultIsExplicitlyDiagnostic() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "opens")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "opens", Map.of("en", "Opens"), AnalyticsMetricType.EVENT_COUNT,
                        objectMapper.readTree("""
                                {"semanticEvent":"app.open","schemaScope":"CROSS_VERSION_VERIFIED",
                                 "schemaScopeReason":"The event meaning is verified across contract versions."}
                                """),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.open")))
                .thenReturn(Map.of("app.open", List.of()));
        AnalyticsPropertyFilterService filters = mock(AnalyticsPropertyFilterService.class);
        when(filters.compile(eq("project"), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration, mock(AdminProductAnalyticsService.class), filters, semantics,
                new ActorIdentityResolver(), dataSources, new AnalyticsQueryProperties(),
                mock(ProjectTransactionExecutor.class), objectMapper
        );

        var result = service.evaluate(
                "project", "opens", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        );

        assertThat(result.resultClassification())
                .isEqualTo(AnalyticsMetricResultClassification.CROSS_VERSION_DIAGNOSTIC);
        assertThat(result.diagnosticReason())
                .isEqualTo("The event meaning is verified across contract versions.");
        assertThat(result.result().path("occurrences").asLong()).isZero();
    }

    @Test
    void metricWithoutProjectSchemaPolicyIsExplicitlyUngoverned() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "opens")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "opens", Map.of("en", "Opens"), AnalyticsMetricType.EVENT_COUNT,
                        objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.open")))
                .thenReturn(Map.of("app.open", List.of()));
        AnalyticsPropertyFilterService filters = mock(AnalyticsPropertyFilterService.class);
        when(filters.compile(eq("project"), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration, mock(AdminProductAnalyticsService.class), filters, semantics,
                new ActorIdentityResolver(), dataSources, new AnalyticsQueryProperties(),
                mock(ProjectTransactionExecutor.class), objectMapper
        );

        var result = service.evaluate(
                "project", "opens", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        );

        assertThat(result.resultClassification())
                .isEqualTo(AnalyticsMetricResultClassification.UNGOVERNED_DIAGNOSTIC);
        assertThat(result.diagnosticReason()).isNull();
    }

    @Test
    void emptySemanticMappingStillRejectsInactiveProject() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "opens")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "opens", Map.of("en", "Opens"), AnalyticsMetricType.EVENT_COUNT,
                        objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.open")))
                .thenReturn(Map.of("app.open", List.of()));
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", false
        ));
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration,
                mock(AdminProductAnalyticsService.class),
                mock(AnalyticsPropertyFilterService.class),
                semantics,
                new ActorIdentityResolver(),
                dataSources,
                new AnalyticsQueryProperties(),
                mock(ProjectTransactionExecutor.class),
                objectMapper
        );

        assertThatThrownBy(() -> service.evaluate(
                "project", "opens", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("PROJECT_INACTIVE")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventCountMetricRejectsAnOversizedEventWindowBeforeAggregating() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "opens")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "opens", Map.of("en", "Opens"), AnalyticsMetricType.EVENT_COUNT,
                        objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.open")))
                .thenReturn(Map.of("app.open", List.of("app_open")));
        AnalyticsPropertyFilterService filters = mock(AnalyticsPropertyFilterService.class);
        when(filters.compile(eq("project"), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        DataSource projectDataSource = mock(DataSource.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        when(dataSources.getDataSource("project")).thenReturn(projectDataSource);
        when(dataSources.getTableName("project", "events")).thenReturn("analytics_events");
        when(dataSources.getTableName("project", "actor_identity_links")).thenReturn("actor_identity_links");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("bounded_candidates"), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        ProjectTransactionExecutor transactions = mock(ProjectTransactionExecutor.class);
        when(transactions.executeReadOnly(eq(projectDataSource), anyInt(), any()))
                .thenAnswer(invocation -> ((Function<JdbcTemplate, Object>) invocation.getArgument(2)).apply(jdbc));
        AnalyticsQueryProperties queryProperties = new AnalyticsQueryProperties();
        queryProperties.setMaxCandidateRows(1);
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration, mock(AdminProductAnalyticsService.class), filters, semantics,
                new ActorIdentityResolver(), dataSources, queryProperties, transactions, objectMapper
        );

        assertThatThrownBy(() -> service.evaluate(
                "project", "opens", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void uniqueActorMetricRejectsOversizedJvmCandidateSetWithoutReturningPartialCount() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "active_users")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "active_users", Map.of("en", "Active users"),
                        AnalyticsMetricType.UNIQUE_ACTORS,
                        objectMapper.readTree("{\"semanticEvent\":\"app.active\"}"),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.active")))
                .thenReturn(Map.of("app.active", List.of("app_foreground")));
        AnalyticsPropertyFilterService filters = mock(AnalyticsPropertyFilterService.class);
        when(filters.compile(eq("project"), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        DataSource projectDataSource = mock(DataSource.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        when(dataSources.getDataSource("project")).thenReturn(projectDataSource);
        when(dataSources.getTableName("project", "events")).thenReturn("analytics_events");
        when(dataSources.getTableName("project", "actor_identity_links")).thenReturn("actor_identity_links");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("bounded_candidates"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("actor-a", "actor-b"));
        ProjectTransactionExecutor transactions = mock(ProjectTransactionExecutor.class);
        when(transactions.executeReadOnly(eq(projectDataSource), anyInt(), any()))
                .thenAnswer(invocation -> ((Function<JdbcTemplate, Object>) invocation.getArgument(2)).apply(jdbc));
        AnalyticsQueryProperties queryProperties = new AnalyticsQueryProperties();
        queryProperties.setMaxCandidateRows(1);
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration, mock(AdminProductAnalyticsService.class), filters, semantics,
                new ActorIdentityResolver(), dataSources, queryProperties, transactions, objectMapper
        );

        assertThatThrownBy(() -> service.evaluate(
                "project", "active_users", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void uniqueActorMetricRejectsOversizedMatchingEventWindowBeforeDistinctActors() {
        var objectMapper = JsonMapper.builder().build();
        AnalysisConfigurationService configuration = mock(AnalysisConfigurationService.class);
        when(configuration.getMetric("project", "active_users")).thenReturn(
                new AnalyticsMetricDefinitionResponse(
                        "project", "active_users", Map.of("en", "Active users"),
                        AnalyticsMetricType.UNIQUE_ACTORS,
                        objectMapper.readTree("{\"semanticEvent\":\"app.active\"}"),
                        null, true, Instant.EPOCH, Instant.EPOCH
                )
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases("project", List.of("app.active")))
                .thenReturn(Map.of("app.active", List.of("app_foreground")));
        AnalyticsPropertyFilterService filters = mock(AnalyticsPropertyFilterService.class);
        when(filters.compile(eq("project"), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        DataSource projectDataSource = mock(DataSource.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        when(dataSources.getDataSource("project")).thenReturn(projectDataSource);
        when(dataSources.getTableName("project", "events")).thenReturn("analytics_events");
        when(dataSources.getTableName("project", "actor_identity_links")).thenReturn("actor_identity_links");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("bounded_candidates"), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        ProjectTransactionExecutor transactions = mock(ProjectTransactionExecutor.class);
        when(transactions.executeReadOnly(eq(projectDataSource), anyInt(), any()))
                .thenAnswer(invocation -> ((Function<JdbcTemplate, Object>) invocation.getArgument(2)).apply(jdbc));
        AnalyticsQueryProperties queryProperties = new AnalyticsQueryProperties();
        queryProperties.setMaxCandidateRows(1);
        AnalyticsMetricEvaluationService service = new AnalyticsMetricEvaluationService(
                configuration, mock(AdminProductAnalyticsService.class), filters, semantics,
                new ActorIdentityResolver(), dataSources, queryProperties, transactions, objectMapper
        );

        assertThatThrownBy(() -> service.evaluate(
                "project", "active_users", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }
}
