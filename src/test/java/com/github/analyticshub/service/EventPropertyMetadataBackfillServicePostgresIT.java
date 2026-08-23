package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class EventPropertyMetadataBackfillServicePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("event_property_backfill_test")
            .withUsername("backfill_test")
            .withPassword("backfill_test_password");

    private JdbcTemplate jdbcTemplate;
    private EventPropertyMetadataBackfillService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS backfill_events");
        jdbcTemplate.execute("""
                CREATE TABLE backfill_events (
                    id BIGSERIAL PRIMARY KEY,
                    project_id VARCHAR(50) NOT NULL,
                    properties JSONB,
                    properties_size_bytes INTEGER,
                    identity_scope VARCHAR(64)
                )
                """);

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        when(dataSourceManager.getDataSource("test_project")).thenReturn(dataSource);
        when(dataSourceManager.getTableName("test_project", "events")).thenReturn("backfill_events");
        service = new EventPropertyMetadataBackfillService(
                dataSourceManager,
                new ProjectTransactionExecutor(),
                new EventMetadataSchemaSupport()
        );
    }

    @Test
    void backfillsBoundedBatchesAndResumesFromRemainingRows() {
        insert("test_project", "{\"identity_scope\":\"anonymous\",\"step\":1}");
        insert("test_project", "{\"identity_scope\":\"cloud_account\",\"step\":2}");
        insert("test_project", null);
        insert("other_project", "{\"identity_scope\":\"anonymous\"}");

        assertThat(service.backfillNextBatch("test_project", 2)).isEqualTo(2);
        assertThat(metadataCount("test_project")).isEqualTo(2);
        assertThat(service.backfillNextBatch("test_project", 2)).isEqualTo(1);
        assertThat(metadataCount("test_project")).isEqualTo(3);
        assertThat(service.backfillNextBatch("test_project", 2)).isZero();
        assertThat(metadataCount("other_project")).isZero();

        Map<String, Object> first = jdbcTemplate.queryForMap(
                "SELECT properties::text AS properties, properties_size_bytes, identity_scope "
                        + "FROM backfill_events WHERE project_id = ? ORDER BY id LIMIT 1",
                "test_project"
        );
        assertThat(first.get("properties_size_bytes")).isEqualTo(
                first.get("properties").toString().getBytes(StandardCharsets.UTF_8).length
        );
        assertThat(first.get("identity_scope")).isEqualTo("anonymous");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT properties_size_bytes FROM backfill_events WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class,
                "test_project"
        )).isZero();
    }

    @Test
    void rejectsOversizedOrNonStringIdentityScopeWithoutBlockingMetadata() {
        String oversizedScope = "x".repeat(65);
        insert("test_project", "{\"identity_scope\":\"" + oversizedScope + "\"}");
        insert("test_project", "{\"identity_scope\":{\"kind\":\"anonymous\"}}");

        assertThat(service.backfillNextBatch("test_project", 1000)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForList(
                "SELECT identity_scope FROM backfill_events ORDER BY id",
                String.class
        )).containsExactly(null, null);
        assertThat(metadataCount("test_project")).isEqualTo(2);
    }

    @Test
    void largeLegacySetCompletesThroughStrictlyBoundedBatches() {
        for (int index = 0; index < 250; index++) {
            insert("test_project", "{\"identity_scope\":\"anonymous\",\"index\":" + index + "}");
        }

        int totalProcessed = 0;
        int nonEmptyBatches = 0;
        int processed;
        do {
            processed = service.backfillNextBatch("test_project", 20);
            assertThat(processed).isBetween(0, 20);
            if (processed > 0) {
                nonEmptyBatches++;
                totalProcessed += processed;
            }
        } while (processed > 0);

        assertThat(totalProcessed).isEqualTo(250);
        assertThat(nonEmptyBatches).isEqualTo(13);
        assertThat(metadataCount("test_project")).isEqualTo(250);
    }

    @Test
    void v7ProjectIsSkippedWithoutQueryingColumnsThatDoNotExist() {
        jdbcTemplate.execute("ALTER TABLE backfill_events DROP COLUMN properties_size_bytes");
        jdbcTemplate.execute("ALTER TABLE backfill_events DROP COLUMN identity_scope");

        assertThat(service.backfillNextBatch("test_project", 20)).isZero();
    }

    private void insert(String projectId, String properties) {
        jdbcTemplate.update(
                "INSERT INTO backfill_events (project_id, properties) VALUES (?, ?::jsonb)",
                projectId,
                properties
        );
    }

    private int metadataCount(String projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM backfill_events WHERE project_id = ? AND properties_size_bytes IS NOT NULL",
                Integer.class,
                projectId
        );
    }
}
