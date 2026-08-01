package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.AdminFunnelGroupResult;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminRetentionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AdminProductAnalyticsServicePostgresIT {

    private static final String PROJECT_ID = "product_analytics_project";
    private static final String PREFIX = "product_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("product_analytics_test")
            .withUsername("product_test")
            .withPassword("product_test_password");

    private JdbcTemplate jdbcTemplate;
    private AdminProductAnalyticsService service;

    @BeforeEach
    void setUp() {
        String schema = "product_analytics_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig projectConfig = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Product Analytics Test",
                "localhost",
                5432,
                "product_analytics_test",
                schema,
                "product_test",
                "product_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "events")).thenReturn(quoted(PREFIX + "events"));

        service = new AdminProductAnalyticsService(dataSourceManager, JsonMapper.builder().build());
    }

    @Test
    void funnelPreservesEventOrderAndFirstTouchGrouping() {
        UUID organicUser = UUID.randomUUID();
        UUID adUser = UUID.randomUUID();
        UUID reorderedUser = UUID.randomUUID();

        insertEvent(organicUser, "landing", "2026-01-01T01:00:00Z", "{\"source\":\"organic\"}");
        insertEvent(organicUser, "checkout", "2026-01-01T01:05:00Z", null);
        insertEvent(organicUser, "purchase", "2026-01-01T01:10:00Z", null);
        // A later first-step event must not move this actor to another attribution group.
        insertEvent(organicUser, "landing", "2026-01-01T01:15:00Z", "{\"source\":\"ad\"}");

        insertEvent(adUser, "landing", "2026-01-01T02:00:00Z", "{\"source\":\"ad\"}");
        insertEvent(adUser, "purchase", "2026-01-01T02:05:00Z", null);

        // Checkout before entry does not satisfy step two; the later checkout does.
        insertEvent(reorderedUser, "checkout", "2026-01-01T02:55:00Z", null);
        insertEvent(reorderedUser, "landing", "2026-01-01T03:00:00Z", "{\"source\":\"organic\"}");
        insertEvent(reorderedUser, "checkout", "2026-01-01T03:05:00Z", null);

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,checkout,purchase",
                "source"
        );

        assertThat(response.attributionModel()).isEqualTo("first_touch_actor");
        assertThat(response.groups()).extracting(AdminFunnelGroupResult::groupValue)
                .containsExactly("ad", "organic");

        AdminFunnelGroupResult ad = response.groups().getFirst();
        assertThat(ad.steps()).extracting(step -> step.users()).containsExactly(1L, 0L, 0L);
        assertThat(ad.steps()).extracting(step -> step.conversionRate()).containsExactly(1d, 0d, 0d);
        assertThat(ad.steps()).extracting(step -> step.dropOffRate()).containsExactly(0d, 1d, 0d);

        AdminFunnelGroupResult organic = response.groups().get(1);
        assertThat(organic.steps()).extracting(step -> step.users()).containsExactly(2L, 2L, 1L);
        assertThat(organic.steps()).extracting(step -> step.conversionRate()).containsExactly(1d, 1d, 0.5d);
        assertThat(organic.steps()).extracting(step -> step.dropOffRate()).containsExactly(0d, 0d, 0.5d);
    }

    @Test
    void retentionUsesCohortRelativeDayWindowsAndDeduplicatesActors() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        insertEvent(firstUser, "signup", "2026-01-01T10:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-01T12:00:00Z", null);
        // D1 is the next UTC calendar day, even when less than 24 hours after signup.
        insertEvent(firstUser, "open", "2026-01-02T09:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-08T11:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-08T12:00:00Z", null);

        insertEvent(secondUser, "signup", "2026-01-01T14:00:00Z", null);
        insertEvent(secondUser, "open", "2026-01-01T15:00:00Z", null);

        AdminRetentionResponse response = service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "signup",
                "open",
                "7,0,1,7"
        );

        assertThat(response.cohortUsers()).isEqualTo(2);
        assertThat(response.buckets()).extracting(bucket -> bucket.day()).containsExactly(0, 1, 7);
        assertThat(response.buckets()).extracting(bucket -> bucket.retainedUsers()).containsExactly(2L, 1L, 1L);
        assertThat(response.buckets()).extracting(bucket -> bucket.retentionRate()).containsExactly(1d, 0.5d, 0.5d);
    }

    @Test
    void funnelRequiresAtLeastTwoDistinctSteps() {
        assertThatThrownBy(() -> service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,landing",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 2 个不同事件");
    }

    private void insertEvent(UUID userId, String eventType, String createdAt, String properties) {
        Instant instant = Instant.parse(createdAt);
        jdbcTemplate.update(
                "INSERT INTO " + quoted(PREFIX + "events") + " "
                        + "(event_id, device_id, user_id, event_type, event_timestamp, properties, project_id, created_at) "
                        + "VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?, ?)",
                "evt_" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                userId.toString(),
                eventType,
                instant.toEpochMilli(),
                properties,
                PROJECT_ID,
                Timestamp.from(instant)
        );
    }

    private static String quoted(String identifier) {
        return '"' + identifier + '"';
    }
}
