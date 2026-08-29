package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AnalyticsMetricEvaluationServicePostgresIT {

    private static final String PROJECT_ID = "metric_evaluation_project";
    private static final long RANGE_START = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("metric_evaluation_test")
            .withUsername("metric_test")
            .withPassword("metric_test_password");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private AnalysisConfigurationService configuration;
    private SemanticDictionaryService semantics;
    private AnalyticsPropertyFilterService propertyFilters;
    private AnalyticsMetricEvaluationService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS analytics_events (
                    project_id TEXT NOT NULL,
                    event_timestamp BIGINT NOT NULL,
                    event_type TEXT NOT NULL,
                    user_id TEXT,
                    device_id UUID NOT NULL,
                    properties JSONB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS analytics_actor_identity_links (
                    project_id TEXT NOT NULL,
                    source_actor_id UUID NOT NULL,
                    canonical_actor_id UUID NOT NULL
                )
                """);
        jdbc.update("TRUNCATE analytics_events, analytics_actor_identity_links");

        configuration = mock(AnalysisConfigurationService.class);
        semantics = mock(SemanticDictionaryService.class);
        propertyFilters = mock(AnalyticsPropertyFilterService.class);
        when(propertyFilters.compile(eq(PROJECT_ID), any(), eq("properties")))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());

        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        when(dataSources.getProjectConfig(PROJECT_ID)).thenReturn(new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID, "Metric project", "localhost", POSTGRES.getFirstMappedPort(),
                POSTGRES.getDatabaseName(), "public", POSTGRES.getUsername(), POSTGRES.getPassword(),
                "analytics_", true
        ));
        when(dataSources.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSources.getTableName(PROJECT_ID, "events")).thenReturn("analytics_events");
        when(dataSources.getTableName(PROJECT_ID, "actor_identity_links"))
                .thenReturn("analytics_actor_identity_links");

        service = new AnalyticsMetricEvaluationService(
                configuration,
                mock(AdminProductAnalyticsService.class),
                propertyFilters,
                semantics,
                new ActorIdentityResolver(),
                dataSources,
                new AnalyticsQueryProperties(),
                new ProjectTransactionExecutor(),
                objectMapper
        );
    }

    @Test
    void propertyBreakdownUsesCanonicalActorsLabelsAndMissingBucket() {
        when(configuration.getMetric(PROJECT_ID, "mode_mix")).thenReturn(metric(
                "mode_mix", AnalyticsMetricType.PROPERTY_BREAKDOWN, """
                        {"semanticEvent":"authoring.started","aggregation":"UNIQUE_ACTORS",
                         "groupBy":"authoring_mode","missingValuePolicy":"INCLUDE",
                         "valueLabels":{"light":{"zh-CN":"轻量写信","en":"Light Authoring"}}}
                        """
        ));
        when(semantics.resolveActiveEventAliases(PROJECT_ID, List.of("authoring.started")))
                .thenReturn(Map.of("authoring.started", List.of("authoring_started")));
        when(propertyFilters.requireGroupable(PROJECT_ID, "authoring_mode"))
                .thenReturn(AnalyticsPropertyDataType.STRING);

        String anonymous = "11111111-1111-1111-1111-111111111111";
        String cloud = "22222222-2222-2222-2222-222222222222";
        String fullActor = "33333333-3333-3333-3333-333333333333";
        String missingActor = "44444444-4444-4444-4444-444444444444";
        jdbc.update("INSERT INTO analytics_actor_identity_links VALUES (?, ?::uuid, ?::uuid)",
                PROJECT_ID, anonymous, cloud);
        insertEvent("authoring_started", null, anonymous, "{\"authoring_mode\":\"light\"}", 1);
        insertEvent("authoring_started", cloud, cloud, "{\"authoring_mode\":\"light\"}", 2);
        insertEvent("authoring_started", null, fullActor, "{\"authoring_mode\":\"full\"}", 3);
        insertEvent("authoring_started", null, fullActor, "{\"authoring_mode\":\"full\"}", 4);
        insertEvent("authoring_started", null, missingActor, "{}", 5);

        var result = service.evaluate(
                PROJECT_ID, "mode_mix", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        ).result();

        assertThat(result.path("totalMeasure").asLong()).isEqualTo(3);
        assertThat(result.path("rows")).hasSize(3);
        assertThat(row(result, "light").path("measure").asLong()).isEqualTo(1);
        assertThat(row(result, "light").path("displayName").path("zh-CN").asString())
                .isEqualTo("轻量写信");
        assertThat(row(result, "full").path("measure").asLong()).isEqualTo(1);
        assertThat(result.path("rows")).anySatisfy(item -> {
            assertThat(item.path("missing").asBoolean()).isTrue();
            assertThat(item.path("measure").asLong()).isEqualTo(1);
        });
    }

    @Test
    void numericSummaryCalculatesAllStatisticsAndIgnoresNonNumericValues() {
        when(configuration.getMetric(PROJECT_ID, "duration_summary")).thenReturn(metric(
                "duration_summary", AnalyticsMetricType.NUMERIC_PROPERTY_SUMMARY, """
                        {"semanticEvent":"opening.ended","propertyKey":"duration_ms","unit":"MILLISECONDS"}
                        """
        ));
        when(semantics.resolveActiveEventAliases(PROJECT_ID, List.of("opening.ended")))
                .thenReturn(Map.of("opening.ended", List.of("opening_ended")));
        when(propertyFilters.requireNumericSummary(PROJECT_ID, "duration_ms"))
                .thenReturn(AnalyticsPropertyDataType.NUMBER);

        insertEvent("opening_ended", null, "55555555-5555-5555-5555-555555555555", "{\"duration_ms\":10}", 1);
        insertEvent("opening_ended", null, "66666666-6666-6666-6666-666666666666", "{\"duration_ms\":20}", 2);
        insertEvent("opening_ended", null, "77777777-7777-7777-7777-777777777777", "{\"duration_ms\":30}", 3);
        insertEvent("opening_ended", null, "88888888-8888-8888-8888-888888888888", "{\"duration_ms\":40}", 4);
        insertEvent("opening_ended", null, "99999999-9999-9999-9999-999999999999", "{\"duration_ms\":\"invalid\"}", 5);

        var result = service.evaluate(
                PROJECT_ID, "duration_summary", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        ).result();

        assertThat(result.path("sampleCount").asInt()).isEqualTo(4);
        assertThat(result.path("average").asDouble()).isEqualTo(25D);
        assertThat(result.path("median").asDouble()).isEqualTo(20D);
        assertThat(result.path("p90").asDouble()).isEqualTo(40D);
        assertThat(result.path("min").asDouble()).isEqualTo(10D);
        assertThat(result.path("max").asDouble()).isEqualTo(40D);
    }

    private AnalyticsMetricDefinitionResponse metric(
            String metricKey,
            AnalyticsMetricType type,
            String definition
    ) {
        return new AnalyticsMetricDefinitionResponse(
                PROJECT_ID, metricKey, Map.of("en", metricKey), type,
                objectMapper.readTree(definition), null, true, Instant.EPOCH, Instant.EPOCH
        );
    }

    private void insertEvent(
            String eventType,
            String userId,
            String deviceId,
            String properties,
            int offset
    ) {
        jdbc.update(
                "INSERT INTO analytics_events VALUES (?, ?, ?, ?, ?::uuid, ?::jsonb)",
                PROJECT_ID, RANGE_START + offset, eventType, userId, deviceId, properties
        );
    }

    private static tools.jackson.databind.JsonNode row(
            tools.jackson.databind.JsonNode result,
            String value
    ) {
        for (var item : result.path("rows")) {
            if (value.equals(item.path("value").asString())) return item;
        }
        throw new AssertionError("missing row: " + value);
    }
}
