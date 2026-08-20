package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.dto.AdminDashboardRecord;
import com.github.analyticshub.dto.AdminDashboardUpsertRequest;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AdminDashboardServicePostgresIT {

    private static final String SYSTEM_SCHEMA = "dashboard_system_it";
    private static final String PROJECT_ID = "dashboard_project";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("dashboard_service_test")
            .withUsername("dashboard_test")
            .withPassword("dashboard_test_password");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transaction;
    private AdminDashboardService service;
    private SemanticDictionaryService semanticDictionaryService;
    private CounterService counterService;

    @BeforeEach
    void setUp() {
        DataSource rootDataSource = dataSource(POSTGRES.getJdbcUrl());
        JdbcTemplate rootJdbcTemplate = new JdbcTemplate(rootDataSource);
        rootJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SYSTEM_SCHEMA + " CASCADE");
        rootJdbcTemplate.execute("CREATE SCHEMA " + SYSTEM_SCHEMA);

        DataSource systemDataSource = dataSource(withCurrentSchema(POSTGRES.getJdbcUrl(), SYSTEM_SCHEMA));
        Flyway.configure()
                .dataSource(systemDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(SYSTEM_SCHEMA)
                .schemas(SYSTEM_SCHEMA)
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(systemDataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(systemDataSource));

        jdbcTemplate.update(
                """
                INSERT INTO analytics_projects
                    (project_id, project_name, db_host, db_port, db_name, db_schema, db_user,
                     table_prefix, is_active)
                VALUES (?, 'Dashboard Test', 'localhost', 5432, 'project_db', 'analytics',
                        'project_user', '', TRUE)
                """,
                PROJECT_ID
        );

        AnalyticsProject project = new AnalyticsProject();
        project.setProjectId(PROJECT_ID);
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        when(projectMapper.selectOne(any())).thenReturn(project);

        semanticDictionaryService = mock(SemanticDictionaryService.class);
        when(semanticDictionaryService.resolveAvailableActiveEventAliases(any(), any()))
                .thenReturn(Map.of());
        counterService = mock(CounterService.class);
        when(counterService.existingKeys(any(), any())).thenReturn(java.util.Set.of());
        service = new AdminDashboardService(
                projectMapper,
                jdbcTemplate,
                objectMapper,
                new DashboardDefinitionValidator(),
                new DashboardOverviewMetricPolicy(semanticDictionaryService),
                new DashboardCounterPolicy(counterService)
        );
    }

    @Test
    void crudPreservesOmittedStateAndUsesRevisionForUpdateAndDelete() {
        AdminDashboardRecord operations = upsert(
                "operations",
                request("Operations", null, true, true)
        );
        assertThat(operations.revision()).isEqualTo(1);
        assertThat(operations.isDefault()).isTrue();
        assertThat(operations.isActive()).isTrue();

        AdminDashboardRecord product = upsert(
                "product",
                request("Product", 0L, true, true)
        );
        assertThat(product.revision()).isEqualTo(1);
        assertThat(product.isDefault()).isTrue();

        AdminDashboardRecord demotedOperations = service.get(PROJECT_ID, "operations");
        assertThat(demotedOperations.revision()).isEqualTo(2);
        assertThat(demotedOperations.isDefault()).isFalse();

        AdminDashboardRecord updated = upsert(
                "operations",
                request("Operations v2", demotedOperations.revision(), null, null)
        );
        assertThat(updated.revision()).isEqualTo(3);
        assertThat(updated.isDefault()).isFalse();
        assertThat(updated.isActive()).isTrue();
        assertThat(updated.displayName()).containsEntry("en", "Operations v2");

        assertBusinessError(
                () -> delete("operations", 2L),
                "DASHBOARD_REVISION_CONFLICT",
                409
        );
        assertThat(service.get(PROJECT_ID, "operations").revision()).isEqualTo(3);

        delete("operations", 3L);
        assertBusinessError(
                () -> service.get(PROJECT_ID, "operations"),
                "DASHBOARD_NOT_FOUND",
                404
        );

        List<AdminDashboardRecord> remaining = service.list(PROJECT_ID);
        assertThat(remaining).extracting(AdminDashboardRecord::dashboardKey).containsExactly("product");
    }

    @Test
    void concurrentUpdatesWithTheSameRevisionHaveExactlyOneWinner() throws Exception {
        upsert("shared", request("Initial", null, false, true));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MutationAttempt> first = executor.submit(
                    () -> updateAfter(ready, start, "First writer")
            );
            Future<MutationAttempt> second = executor.submit(
                    () -> updateAfter(ready, start, "Second writer")
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MutationAttempt firstAttempt = first.get(10, TimeUnit.SECONDS);
            MutationAttempt secondAttempt = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstAttempt, secondAttempt).stream()
                    .filter(attempt -> attempt.record() != null)
                    .count()).isEqualTo(1);
            assertThat(List.of(firstAttempt, secondAttempt).stream()
                    .filter(attempt -> attempt.error() != null)
                    .map(attempt -> attempt.error().getCode()))
                    .containsExactly("DASHBOARD_REVISION_CONFLICT");

            AdminDashboardRecord stored = service.get(PROJECT_ID, "shared");
            assertThat(stored.revision()).isEqualTo(2);
            assertThat(stored.displayName().get("en"))
                    .isIn("First writer", "Second writer");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDefaultChangesNeverCreateMultipleDefaults() throws Exception {
        upsert("dashboard_a", request("Dashboard A", null, false, true));
        upsert("dashboard_b", request("Dashboard B", null, false, true));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AdminDashboardRecord> first = executor.submit(
                    () -> makeDefaultAfter(ready, start, "dashboard_a", "Dashboard A")
            );
            Future<AdminDashboardRecord> second = executor.submit(
                    () -> makeDefaultAfter(ready, start, "dashboard_b", "Dashboard B")
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();

            List<AdminDashboardRecord> dashboards = service.list(PROJECT_ID);
            assertThat(dashboards).filteredOn(AdminDashboardRecord::isDefault).hasSize(1);
            assertThat(dashboards).filteredOn(AdminDashboardRecord::isDefault)
                    .allMatch(AdminDashboardRecord::isActive);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void migrationConstraintsDefendTheDefaultAndJsonInvariants() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition,
                     is_default, is_active)
                VALUES (?, 'unsafe_state', '{"en":"Unsafe"}'::jsonb, 1,
                        '{"schemaVersion":1,"widgets":[]}'::jsonb, TRUE, FALSE)
                """,
                PROJECT_ID
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition)
                VALUES (?, 'unsafe_json', '[]'::jsonb, 1, '[]'::jsonb)
                """,
                PROJECT_ID
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnmappedBusinessOverviewMetricsButAllowsMappedOrSystemMetrics() {
        assertThat(upsert(
                "system_only",
                requestWithDefinition(
                        "System only",
                        overviewDefinition(List.of(OverviewMetricCatalog.ACTIVE_DEVICES))
                )
        ).dashboardKey()).isEqualTo("system_only");

        AdminDashboardUpsertRequest unmapped = requestWithDefinition(
                "Unmapped",
                overviewDefinition(List.of(OverviewMetricCatalog.ACCOUNT_CREATED))
        );
        assertBusinessError(
                () -> upsert("unmapped", unmapped),
                "DASHBOARD_METRIC_UNAVAILABLE",
                400
        );

        when(semanticDictionaryService.resolveAvailableActiveEventAliases(any(), any()))
                .thenReturn(Map.of(
                        OverviewMetricCatalog.ACCOUNT_CREATED,
                        List.of("cloud_account_created")
                ));
        AdminDashboardRecord mapped = upsert(
                "mapped",
                requestWithDefinition(
                        "Mapped",
                        overviewDefinition(List.of(
                                OverviewMetricCatalog.ACTIVE_DEVICES,
                                OverviewMetricCatalog.ACCOUNT_CREATED
                        ))
                )
        );
        assertThat(mapped.definition()).isEqualTo(overviewDefinition(List.of(
                OverviewMetricCatalog.ACTIVE_DEVICES,
                OverviewMetricCatalog.ACCOUNT_CREATED
        )));

        when(semanticDictionaryService.resolveAvailableActiveEventAliases(any(), any()))
                .thenReturn(Map.of());
        AdminDashboardRecord layoutOnlyUpdate = upsert(
                "mapped",
                new AdminDashboardUpsertRequest(
                        Map.of("en", "Mapped layout update"),
                        "Overview metric policy",
                        1,
                        overviewDefinition(List.of(
                                OverviewMetricCatalog.ACTIVE_DEVICES,
                                OverviewMetricCatalog.ACCOUNT_CREATED
                        )),
                        mapped.revision(),
                        false,
                        true
                )
        );
        assertThat(layoutOnlyUpdate.revision()).isEqualTo(mapped.revision() + 1);

        assertBusinessError(
                () -> upsert(
                        "mapped",
                        new AdminDashboardUpsertRequest(
                                Map.of("en", "Invalid new metric"),
                                "Overview metric policy",
                                1,
                                overviewDefinition(List.of(
                                        OverviewMetricCatalog.ACCOUNT_CREATED,
                                        OverviewMetricCatalog.ACCOUNT_RECREATED
                                )),
                                layoutOnlyUpdate.revision(),
                                false,
                                true
                        )
                ),
                "DASHBOARD_METRIC_UNAVAILABLE",
                400
        );
    }

    @Test
    void validatesNewCounterReferencesWithoutBlockingRetainedStaleKeys() {
        when(counterService.existingKeys(any(), any()))
                .thenReturn(java.util.Set.of("letters_completed", "shares_completed"));
        AdminDashboardRecord created = upsert(
                "counters",
                requestWithDefinition(
                        "Counters",
                        counterDefinition(List.of("shares_completed", "letters_completed"))
                )
        );
        assertThat(created.definition()).isEqualTo(
                counterDefinition(List.of("shares_completed", "letters_completed"))
        );

        when(counterService.existingKeys(any(), any())).thenReturn(java.util.Set.of());
        AdminDashboardRecord retained = upsert(
                "counters",
                new AdminDashboardUpsertRequest(
                        Map.of("en", "Counters layout update"),
                        "Counter reference policy",
                        1,
                        counterDefinition(List.of("shares_completed", "letters_completed")),
                        created.revision(),
                        false,
                        true
                )
        );
        assertThat(retained.revision()).isEqualTo(created.revision() + 1);

        assertBusinessError(
                () -> upsert(
                        "counters",
                        new AdminDashboardUpsertRequest(
                                Map.of("en", "Invalid counter"),
                                "Counter reference policy",
                                1,
                                counterDefinition(List.of(
                                        "shares_completed",
                                        "letters_completed",
                                        "missing_counter"
                                )),
                                retained.revision(),
                                false,
                                true
                        )
                ),
                "DASHBOARD_COUNTER_UNAVAILABLE",
                400
        );
    }

    private MutationAttempt updateAfter(
            CountDownLatch ready,
            CountDownLatch start,
            String displayName
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return new MutationAttempt(
                    upsert("shared", request(displayName, 1L, false, true)),
                    null
            );
        } catch (BusinessException exception) {
            return new MutationAttempt(null, exception);
        }
    }

    private AdminDashboardRecord makeDefaultAfter(
            CountDownLatch ready,
            CountDownLatch start,
            String dashboardKey,
            String displayName
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return upsert(dashboardKey, request(displayName, 1L, true, true));
    }

    private AdminDashboardRecord upsert(String dashboardKey, AdminDashboardUpsertRequest request) {
        return transaction.execute(status -> service.upsert(PROJECT_ID, dashboardKey, request));
    }

    private void delete(String dashboardKey, long revision) {
        transaction.executeWithoutResult(status -> service.delete(PROJECT_ID, dashboardKey, revision));
    }

    private AdminDashboardUpsertRequest request(
            String displayName,
            Long expectedRevision,
            Boolean isDefault,
            Boolean isActive
    ) {
        return new AdminDashboardUpsertRequest(
                Map.of("en", displayName),
                "Generic project dashboard",
                1,
                definition(),
                expectedRevision,
                isDefault,
                isActive
        );
    }

    private Map<String, Object> definition() {
        return Map.of(
                "schemaVersion", 1,
                "widgets", List.of()
        );
    }

    private AdminDashboardUpsertRequest requestWithDefinition(
            String displayName,
            Map<String, Object> definition
    ) {
        return new AdminDashboardUpsertRequest(
                Map.of("en", displayName),
                "Overview metric policy",
                1,
                definition,
                null,
                false,
                true
        );
    }

    private Map<String, Object> overviewDefinition(List<String> metricKeys) {
        return Map.of(
                "schemaVersion", 1,
                "widgets", List.of(Map.of(
                        "id", "overview",
                        "type", "core.overview",
                        "layout", Map.of("x", 0, "y", 0, "w", 12, "h", 4),
                        "config", Map.of("metricKeys", metricKeys)
                ))
        );
    }

    private Map<String, Object> counterDefinition(List<String> counterKeys) {
        return Map.of(
                "schemaVersion", 1,
                "widgets", List.of(Map.of(
                        "id", "counters",
                        "type", "core.counters",
                        "layout", Map.of("x", 0, "y", 0, "w", 6, "h", 6),
                        "config", Map.of("keys", counterKeys)
                ))
        );
    }

    private static void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            String code,
            int status
    ) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getHttpStatus().value()).isEqualTo(status);
                });
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    }

    private record MutationAttempt(AdminDashboardRecord record, BusinessException error) {
    }
}
