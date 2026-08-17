package com.github.analyticshub.database.project;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProjectSchemaMigratorIT {

    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:15-alpine")
                    .withDatabaseName("analyticshub_project_test")
                    .withUsername("analyticshub")
                    .withPassword("analyticshub");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static ProjectSchemaMigrator migrator;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setPoolName("ProjectSchemaMigratorIT");
        dataSource = new HikariDataSource(config);
        jdbcTemplate = new JdbcTemplate(dataSource);
        migrator = new ProjectSchemaMigrator();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void freshInstallAppliesEveryProjectMigration() {
        String schema = nextSchema("fresh");

        ProjectSchemaMigrationResult result = migrator.migrate(dataSource, schema, "analytics_");

        assertThat(result.initialVersion()).isNull();
        assertThat(result.currentVersion()).isEqualTo("8");
        assertThat(result.migrationsExecuted()).isEqualTo(8);
        assertThat(result.legacyBaselineApplied()).isFalse();
        assertThat(result.historyTable()).isEqualTo("analytics_flyway_history");
        assertThat(result.tables()).containsExactly(
                "analytics_devices",
                "analytics_events",
                "analytics_sessions",
                "analytics_traffic_metrics",
                "analytics_counters",
                "analytics_privacy_requests",
                "analytics_idempotency_keys",
                "analytics_actor_identity_links",
                "analytics_actor_suppressions",
                "analytics_work_order_activities",
                "analytics_work_order_outbox"
        );
        assertAllTablesExist(schema, result.tables());
        assertThat(tableExists(schema, result.historyTable())).isTrue();
        assertThat(indexNames(schema, "analytics_%"))
                .contains("analytics_ix_evt_proj_ts", "analytics_ix_evt_proj_dev_ts");

        ProjectSchemaStatus status = migrator.inspect(dataSource, schema, "analytics_");
        assertThat(status.current()).isTrue();
        assertThat(status.currentVersion()).isEqualTo("8");
        assertThat(status.pendingMigrations()).isZero();
        assertThat(status.migrationHistoryValid()).isTrue();
        assertThat(status.allTablesExist()).isTrue();
    }

    @Test
    void inspectingAnUninitializedProjectIsReadOnly() {
        String schema = nextSchema("inspect");

        ProjectSchemaStatus status = migrator.inspect(dataSource, schema, "inspect_");

        assertThat(status.current()).isFalse();
        assertThat(status.currentVersion()).isNull();
        assertThat(status.pendingMigrations()).isEqualTo(8);
        assertThat(status.historyTableExists()).isFalse();
        assertThat(status.migrationHistoryValid()).isFalse();
        assertThat(status.tables().values()).allMatch(exists -> !exists);
        assertThat(schemaExists(schema)).isFalse();
    }

    @Test
    void emptyPrefixUsesOnlyUnprefixedTablesAndHistory() {
        String schema = nextSchema("empty_prefix");

        ProjectSchemaMigrationResult result = migrator.migrate(dataSource, schema, "");

        assertThat(result.tablePrefix()).isEmpty();
        assertThat(result.historyTable()).isEqualTo("flyway_history");
        assertThat(result.tables()).containsExactly(
                "devices",
                "events",
                "sessions",
                "traffic_metrics",
                "counters",
                "privacy_requests",
                "idempotency_keys",
                "actor_identity_links",
                "actor_suppressions",
                "work_order_activities",
                "work_order_outbox"
        );
        assertAllTablesExist(schema, result.tables());
        assertThat(tableExists(schema, "analytics_events")).isFalse();
    }

    @Test
    void checksumDriftMakesHealthNonCurrent() {
        String schema = nextSchema("checksum");
        ProjectSchemaMigrationResult migration = migrator.migrate(dataSource, schema, "checksum_");
        jdbcTemplate.update(
                "UPDATE %s.%s SET checksum = checksum + 1 WHERE version = '2'"
                        .formatted(quoted(schema), quoted(migration.historyTable()))
        );

        ProjectSchemaStatus status = migrator.inspect(dataSource, schema, "checksum_");

        assertThat(status.historyTableExists()).isTrue();
        assertThat(status.pendingMigrations()).isZero();
        assertThat(status.migrationHistoryValid()).isFalse();
        assertThat(status.current()).isFalse();
    }

    @Test
    void legacyOneZeroZeroSchemaIsBaselinedAndUpgradedWithoutDataLoss() throws Exception {
        String schema = nextSchema("legacy");
        String prefix = "legacy_";
        applyLegacyFixture(schema, prefix);
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO %s.%sevents
                    (event_id, device_id, user_id, event_type, event_timestamp, project_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(quoted(schema), prefix),
                "event-before-upgrade", deviceId, "legacy-user", "legacy_event", 1L, "legacy-project");

        ProjectSchemaMigrationResult result = migrator.migrate(dataSource, schema, prefix);

        assertThat(result.initialVersion()).isEqualTo("1");
        assertThat(result.currentVersion()).isEqualTo("8");
        assertThat(result.migrationsExecuted()).isEqualTo(7);
        assertThat(result.legacyBaselineApplied()).isTrue();
        assertThat(tableExists(schema, prefix + "idempotency_keys")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM %s.%sevents WHERE event_id = ?".formatted(quoted(schema), prefix),
                Integer.class,
                "event-before-upgrade"
        )).isEqualTo(1);
        assertThat(historyVersions(schema, result.historyTable())).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void rerunningMigrationIsANoOp() {
        String schema = nextSchema("rerun");
        migrator.migrate(dataSource, schema, "repeat_");

        ProjectSchemaMigrationResult rerun = migrator.migrate(dataSource, schema, "repeat_");

        assertThat(rerun.initialVersion()).isEqualTo("8");
        assertThat(rerun.currentVersion()).isEqualTo("8");
        assertThat(rerun.migrationsExecuted()).isZero();
        assertThat(rerun.legacyBaselineApplied()).isFalse();
        assertThat(historyVersions(schema, rerun.historyTable())).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void independentPrefixesHaveIndependentTablesIndexesAndHistory() {
        String schema = nextSchema("prefixes");

        ProjectSchemaMigrationResult alpha = migrator.migrate(dataSource, schema, "alpha_");
        ProjectSchemaMigrationResult beta = migrator.migrate(dataSource, schema, "beta_");

        assertThat(alpha.migrationsExecuted()).isEqualTo(8);
        assertThat(beta.migrationsExecuted()).isEqualTo(8);
        assertThat(alpha.historyTable()).isEqualTo("alpha_flyway_history");
        assertThat(beta.historyTable()).isEqualTo("beta_flyway_history");
        assertAllTablesExist(schema, alpha.tables());
        assertAllTablesExist(schema, beta.tables());
        assertThat(tableExists(schema, alpha.historyTable())).isTrue();
        assertThat(tableExists(schema, beta.historyTable())).isTrue();

        List<String> alphaIndexes = indexNames(schema, "alpha_%");
        List<String> betaIndexes = indexNames(schema, "beta_%");
        assertThat(alphaIndexes).isNotEmpty().allMatch(name -> name.startsWith("alpha_"));
        assertThat(betaIndexes).isNotEmpty().allMatch(name -> name.startsWith("beta_"));
    }

    @Test
    void concurrentFreshMigrationsForSamePrefixAreSerializedByPostgresLock() throws Exception {
        String schema = nextSchema("concurrent_fresh");

        List<ProjectSchemaMigrationResult> results = migrateConcurrently(
                schema,
                "concurrent_",
                "concurrent_"
        );

        assertThat(results).allSatisfy(result -> assertThat(result.currentVersion()).isEqualTo("8"));
        assertThat(results).extracting(ProjectSchemaMigrationResult::migrationsExecuted)
                .containsExactlyInAnyOrder(0, 8);
        assertThat(historyVersions(schema, "concurrent_flyway_history"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void concurrentLegacyMigrationsBaselineOnlyOnceAndPreserveData() throws Exception {
        String schema = nextSchema("concurrent_legacy");
        String prefix = "concurrent_legacy_";
        applyLegacyFixture(schema, prefix);

        List<ProjectSchemaMigrationResult> results = migrateConcurrently(schema, prefix, prefix);

        assertThat(results).extracting(ProjectSchemaMigrationResult::migrationsExecuted)
                .containsExactlyInAnyOrder(0, 7);
        assertThat(results).extracting(ProjectSchemaMigrationResult::legacyBaselineApplied)
                .containsExactlyInAnyOrder(false, true);
        assertThat(historyVersions(schema, prefix + "flyway_history"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void concurrentDifferentPrefixesRemainIndependent() throws Exception {
        String schema = nextSchema("concurrent_prefixes");
        jdbcTemplate.execute("CREATE SCHEMA " + quoted(schema));

        List<ProjectSchemaMigrationResult> results = migrateConcurrently(schema, "left_", "right_");

        assertThat(results).extracting(ProjectSchemaMigrationResult::tablePrefix)
                .containsExactlyInAnyOrder("left_", "right_");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.currentVersion()).isEqualTo("8");
            assertAllTablesExist(schema, result.tables());
        });
        assertThat(historyVersions(schema, "left_flyway_history"))
                .endsWith("1", "2", "3", "4", "5", "6", "7", "8")
                .doesNotHaveDuplicates();
        assertThat(historyVersions(schema, "right_flyway_history"))
                .endsWith("1", "2", "3", "4", "5", "6", "7", "8")
                .doesNotHaveDuplicates();
    }

    @Test
    void maximumSupportedPrefixMigratesWithoutIdentifierCollisions() {
        String schema = nextSchema("max_prefix");
        String prefix = "p".repeat(39) + "_";

        ProjectSchemaMigrationResult result = migrator.migrate(dataSource, schema, prefix);

        assertThat(prefix).hasSize(40);
        assertThat(result.historyTable()).hasSizeLessThanOrEqualTo(63);
        assertThat(result.tables()).allMatch(table -> table.length() <= 63);
        assertAllTablesExist(schema, result.tables());
        assertThat(tableExists(schema, result.historyTable())).isTrue();
    }

    @Test
    void partialLegacySchemaIsRejectedInsteadOfSilentlyRepaired() {
        String schema = nextSchema("partial");
        jdbcTemplate.execute("CREATE SCHEMA " + quoted(schema));
        jdbcTemplate.execute("CREATE TABLE " + quoted(schema) + ".partial_events (id BIGINT PRIMARY KEY)");

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, "partial_"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial AnalyticsHub 1.0.0")
                .hasMessageContaining("devices");
        assertThat(tableExists(schema, "partial_flyway_history")).isFalse();
    }

    @Test
    void completeLegacyTableNamesWithMissingColumnAreRejected() throws Exception {
        String schema = nextSchema("legacy_missing_column");
        String prefix = "missing_column_";
        applyLegacyFixture(schema, prefix);
        jdbcTemplate.execute("ALTER TABLE " + quoted(schema) + "." + quoted(prefix + "events")
                + " DROP COLUMN properties CASCADE");

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible AnalyticsHub 1.0.0")
                .hasMessageContaining("events columns mismatch")
                .hasMessageContaining("properties");
        assertThat(tableExists(schema, prefix + "flyway_history")).isFalse();
    }

    @Test
    void legacyColumnTypeDriftIsRejected() throws Exception {
        String schema = nextSchema("legacy_type_drift");
        String prefix = "type_drift_";
        applyLegacyFixture(schema, prefix);
        jdbcTemplate.execute("ALTER TABLE " + quoted(schema) + "." + quoted(prefix + "traffic_metrics")
                + " ALTER COLUMN page_path TYPE TEXT");

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("traffic_metrics columns mismatch")
                .hasMessageContaining("page_path");
        assertThat(tableExists(schema, prefix + "flyway_history")).isFalse();
    }

    @Test
    void legacyMissingUniqueConstraintIsRejected() throws Exception {
        String schema = nextSchema("legacy_missing_key");
        String prefix = "missing_key_";
        applyLegacyFixture(schema, prefix);
        jdbcTemplate.execute("ALTER TABLE " + quoted(schema) + "." + quoted(prefix + "events")
                + " DROP CONSTRAINT " + quoted(prefix + "events_event_id_key"));

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("events missing keys")
                .hasMessageContaining("u:event_id");
        assertThat(tableExists(schema, prefix + "flyway_history")).isFalse();
    }

    @Test
    void legacyMissingRequiredIndexIsRejected() throws Exception {
        String schema = nextSchema("legacy_missing_index");
        String prefix = "missing_index_";
        applyLegacyFixture(schema, prefix);
        jdbcTemplate.execute("DROP INDEX " + quoted(schema) + "." + quoted("idx_events_device_id"));

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or incompatible index")
                .hasMessageContaining("idx_events_device_id");
        assertThat(tableExists(schema, prefix + "flyway_history")).isFalse();
    }

    @Test
    void viewsAreNeverRecognizedAsLegacyTables() {
        String schema = nextSchema("legacy_views");
        String prefix = "views_";
        jdbcTemplate.execute("CREATE SCHEMA " + quoted(schema));
        for (String table : List.of(
                "devices", "events", "sessions", "traffic_metrics", "counters", "privacy_requests")) {
            jdbcTemplate.execute("CREATE VIEW " + quoted(schema) + "." + quoted(prefix + table)
                    + " AS SELECT 1 AS placeholder");
        }

        assertThatThrownBy(() -> migrator.migrate(dataSource, schema, prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected ordinary tables")
                .hasMessageContaining("views");
        assertThat(tableExists(schema, prefix + "flyway_history")).isFalse();
    }

    private static void applyLegacyFixture(String schema, String prefix) throws Exception {
        ClassPathResource resource = new ClassPathResource("db/project-legacy/1.0.0/project-init.sql");
        String sql;
        try (var inputStream = resource.getInputStream()) {
            sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{SCHEMA}}", schema)
                    .replace("{{PREFIX}}", prefix);
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ByteArrayResource(
                    sql.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static List<ProjectSchemaMigrationResult> migrateConcurrently(
            String schema,
            String firstPrefix,
            String secondPrefix
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ProjectSchemaMigrationResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return migrator.migrate(dataSource, schema, firstPrefix);
            });
            Future<ProjectSchemaMigrationResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return migrator.migrate(dataSource, schema, secondPrefix);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertAllTablesExist(String schema, List<String> tables) {
        assertThat(tables).allSatisfy(table -> assertThat(tableExists(schema, table))
                .as("table %s.%s", schema, table)
                .isTrue());
    }

    private static boolean tableExists(String schema, String table) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                Boolean.class,
                schema,
                table
        );
        return Boolean.TRUE.equals(exists);
    }

    private static boolean schemaExists(String schema) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                Boolean.class,
                schema
        );
        return Boolean.TRUE.equals(exists);
    }

    private static List<String> historyVersions(String schema, String historyTable) {
        return jdbcTemplate.queryForList(
                "SELECT version FROM %s.%s WHERE success AND version IS NOT NULL ORDER BY installed_rank"
                        .formatted(quoted(schema), quoted(historyTable)),
                String.class
        );
    }

    private static List<String> indexNames(String schema, String tablePattern) {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename LIKE ? ORDER BY indexname",
                String.class,
                schema,
                tablePattern
        );
    }

    private static String nextSchema(String purpose) {
        return "project_it_" + purpose + "_" + SCHEMA_SEQUENCE.incrementAndGet();
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
