package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventCatalogEntry;
import com.github.analyticshub.dto.EventCatalogResponse;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.SemanticAliasUpdateMode;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticSourceKind;
import com.github.analyticshub.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class SemanticDictionaryServicePostgresIT {

    private static final String SYSTEM_SCHEMA = "semantic_system_it";
    private static final String PROJECT_A_SCHEMA = "semantic_project_a_it";
    private static final String PROJECT_B_SCHEMA = "semantic_project_b_it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("semantic_dictionary_test")
            .withUsername("semantic_test")
            .withPassword("semantic_test_password");

    private DataSource systemDataSource;
    private JdbcTemplate systemJdbcTemplate;
    private JdbcTemplate projectAJdbcTemplate;
    private JdbcTemplate projectBJdbcTemplate;
    private SemanticDictionaryService service;
    private AdminMetricsService adminMetricsService;
    private TransactionTemplate systemTransaction;

    @BeforeEach
    void setUp() {
        DataSource rootDataSource = dataSource(POSTGRES.getJdbcUrl());
        JdbcTemplate rootJdbcTemplate = new JdbcTemplate(rootDataSource);
        rootJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SYSTEM_SCHEMA + " CASCADE");
        rootJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + PROJECT_A_SCHEMA + " CASCADE");
        rootJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + PROJECT_B_SCHEMA + " CASCADE");
        rootJdbcTemplate.execute("CREATE SCHEMA " + SYSTEM_SCHEMA);
        rootJdbcTemplate.execute("CREATE SCHEMA " + PROJECT_A_SCHEMA);
        rootJdbcTemplate.execute("CREATE SCHEMA " + PROJECT_B_SCHEMA);

        systemDataSource = dataSource(withCurrentSchema(POSTGRES.getJdbcUrl(), SYSTEM_SCHEMA));
        Flyway.configure()
                .dataSource(systemDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(SYSTEM_SCHEMA)
                .schemas(SYSTEM_SCHEMA)
                .load()
                .migrate();
        systemJdbcTemplate = new JdbcTemplate(systemDataSource);

        DataSource projectADataSource = dataSource(withCurrentSchema(POSTGRES.getJdbcUrl(), PROJECT_A_SCHEMA));
        DataSource projectBDataSource = dataSource(withCurrentSchema(POSTGRES.getJdbcUrl(), PROJECT_B_SCHEMA));
        projectAJdbcTemplate = new JdbcTemplate(projectADataSource);
        projectBJdbcTemplate = new JdbcTemplate(projectBDataSource);
        createEventsTable(projectAJdbcTemplate);
        createEventsTable(projectBJdbcTemplate);

        insertProject("project_a");
        insertProject("project_b");

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        when(dataSourceManager.getDataSource("project_a")).thenReturn(projectADataSource);
        when(dataSourceManager.getDataSource("project_b")).thenReturn(projectBDataSource);
        when(dataSourceManager.getProjectConfig("project_a")).thenReturn(activeProject("project_a"));
        when(dataSourceManager.getProjectConfig("project_b")).thenReturn(activeProject("project_b"));
        when(dataSourceManager.getTableName("project_a", "events")).thenReturn("events");
        when(dataSourceManager.getTableName("project_b", "events")).thenReturn("events");

        service = new SemanticDictionaryService(systemJdbcTemplate, dataSourceManager, JsonMapper.builder().build());
        adminMetricsService = new AdminMetricsService(dataSourceManager, service);
        systemTransaction = new TransactionTemplate(new DataSourceTransactionManager(systemDataSource));
    }

    @Test
    void mergesManyRawKeysToOneSemanticAndKeepsFullHistoryAggregatesAndUnknownFallback() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant middle = Instant.parse("2026-02-01T00:00:00Z");
        Instant last = Instant.parse("2026-03-01T00:00:00Z");
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v1", first);
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v1", middle);
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v2", last);
        insertEvent(projectAJdbcTemplate, "project_a", "item completed/完成", last);
        insertEvent(projectAJdbcTemplate, "project_a", "unknown.event", middle);

        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed.v1", "item.completed.v2", "item completed/完成"),
                "Content completed"
        );

        EventCatalogResponse catalog = service.getEventCatalog("project_a", "EVENT_TYPE");
        EventCatalogEntry legacy = find(catalog, "item.completed.v1");
        EventCatalogEntry current = find(catalog, "item.completed.v2");
        EventCatalogEntry diverseNaming = find(catalog, "item completed/完成");
        EventCatalogEntry unknown = find(catalog, "unknown.event");

        assertThat(legacy.semanticKey()).isEqualTo("custom.content.completed");
        assertThat(current.semanticKey()).isEqualTo("custom.content.completed");
        assertThat(diverseNaming.semanticKey()).isEqualTo("custom.content.completed");
        assertThat(legacy.mapped()).isTrue();
        assertThat(current.mapped()).isTrue();
        assertThat(legacy.eventCount()).isEqualTo(2);
        assertThat(legacy.firstSeenAt()).isEqualTo(first);
        assertThat(legacy.lastSeenAt()).isEqualTo(middle);

        assertThat(unknown.mapped()).isFalse();
        assertThat(unknown.semanticKey()).isEqualTo("unknown.event");
        assertThat(unknown.displayName()).containsExactlyEntriesOf(Map.of("default", "unknown.event"));
        assertThat(unknown.eventCount()).isEqualTo(1);
    }

    @Test
    void aliasOwnershipConflictReturns409AndRollsBackTheSecondDefinition() {
        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed"),
                "Content completed"
        );

        assertThatThrownBy(() -> upsert(
                "project_a",
                "custom.content.finished",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed"),
                "Content finished"
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("SEMANTIC_ALIAS_CONFLICT");
            assertThat(exception.getHttpStatus().value()).isEqualTo(409);
        });

        assertThat(service.listDefinitions("project_a", "EVENT_TYPE").items())
                .extracting(definition -> definition.semanticKey())
                .containsExactly("custom.content.completed");
    }

    @Test
    void dictionariesAndCatalogResolutionAreIsolatedByProject() {
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed", Instant.parse("2026-01-01T00:00:00Z"));
        insertEvent(projectBJdbcTemplate, "project_b", "item.completed", Instant.parse("2026-01-02T00:00:00Z"));

        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed"),
                "Content completed"
        );
        upsert(
                "project_b",
                "custom.workflow.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed"),
                "Workflow completed"
        );

        assertThat(find(service.getEventCatalog("project_a", "EVENT_TYPE"), "item.completed").semanticKey())
                .isEqualTo("custom.content.completed");
        assertThat(find(service.getEventCatalog("project_b", "EVENT_TYPE"), "item.completed").semanticKey())
                .isEqualTo("custom.workflow.completed");
    }

    @Test
    void topEventsCanAggregateRenamedRawKeysBySemanticKey() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v1", first);
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v1", first.plusSeconds(1));
        insertEvent(projectAJdbcTemplate, "project_a", "item.completed.v2", first.plusSeconds(2));
        insertEvent(projectAJdbcTemplate, "project_a", "unknown.event", first.plusSeconds(3));

        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed.v1", "item.completed.v2"),
                "Content completed"
        );

        AdminMetricsTopEventsResponse semantic = adminMetricsService.getTopEvents(
                "project_a",
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                10,
                "semantic"
        );
        AdminMetricsTopEventsResponse raw = adminMetricsService.getTopEvents(
                "project_a",
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                10,
                "raw"
        );

        assertThat(semantic.items())
                .extracting(item -> Map.entry(item.eventType(), item.count()))
                .containsExactly(
                        Map.entry("custom.content.completed", 3L),
                        Map.entry("unknown.event", 1L)
                );
        assertThat(raw.items())
                .extracting(item -> Map.entry(item.eventType(), item.count()))
                .containsExactly(
                        Map.entry("item.completed.v1", 2L),
                        Map.entry("item.completed.v2", 1L),
                        Map.entry("unknown.event", 1L)
                );
    }

    @Test
    void preserveAndReplaceHaveExplicitAliasSemantics() {
        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of("item.completed.v1"),
                "Old display"
        );
        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.PRESERVE,
                null,
                "New display"
        );

        var preserved = service.listDefinitions("project_a", "EVENT_TYPE").items().getFirst();
        assertThat(preserved.aliases()).containsExactly("item.completed.v1");
        assertThat(preserved.displayName()).containsEntry("default", "New display");

        upsert(
                "project_a",
                "custom.content.completed",
                SemanticAliasUpdateMode.REPLACE,
                List.of(),
                "New display"
        );
        assertThat(service.listDefinitions("project_a", "EVENT_TYPE").items().getFirst().aliases()).isEmpty();
    }

    private void upsert(
            String projectId,
            String semanticKey,
            SemanticAliasUpdateMode mode,
            List<String> aliases,
            String displayName
    ) {
        systemTransaction.executeWithoutResult(status -> service.upsertDefinition(
                projectId,
                semanticKey,
                new SemanticDefinitionUpsertRequest(
                        SemanticSourceKind.EVENT_TYPE,
                        Map.of("default", displayName),
                        "engagement",
                        "A generic analytics definition",
                        true,
                        mode,
                        aliases
                )
        ));
    }

    private void insertProject(String projectId) {
        systemJdbcTemplate.update(
                """
                INSERT INTO analytics_projects
                    (project_id, project_name, db_host, db_port, db_name, db_schema, db_user,
                     table_prefix, is_active)
                VALUES (?, ?, 'localhost', 5432, 'project_db', 'analytics', 'project_user', '', TRUE)
                """,
                projectId,
                projectId
        );
    }

    private static MultiDataSourceManager.ProjectConfig activeProject(String projectId) {
        return new MultiDataSourceManager.ProjectConfig(
                projectId,
                projectId,
                "localhost",
                5432,
                "project_db",
                "analytics",
                "project_user",
                "unused",
                "",
                true
        );
    }

    private static void createEventsTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE events (
                    event_id VARCHAR(64) PRIMARY KEY,
                    project_id VARCHAR(50) NOT NULL,
                    event_type VARCHAR(100) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """);
    }

    private static void insertEvent(
            JdbcTemplate jdbcTemplate,
            String projectId,
            String rawKey,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO events (event_id, project_id, event_type, created_at) VALUES (?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(),
                projectId,
                rawKey,
                Timestamp.from(createdAt)
        );
    }

    private static EventCatalogEntry find(EventCatalogResponse response, String rawKey) {
        return response.items().stream()
                .filter(item -> item.rawKey().equals(rawKey))
                .findFirst()
                .orElseThrow();
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
}
