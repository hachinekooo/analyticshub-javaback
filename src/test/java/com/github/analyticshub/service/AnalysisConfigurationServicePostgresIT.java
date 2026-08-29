package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalysisPackImportRequest;
import com.github.analyticshub.dto.AnalysisPackVersionSnapshot;
import com.github.analyticshub.dto.AdminDashboardUpsertRequest;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AnalysisConfigurationServicePostgresIT {

    private static final String PROJECT_ID = "analysis_pack_project";
    private static final String SYSTEM_SCHEMA = "analysis_pack_system_it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("analysis_pack_test")
            .withUsername("analysis_pack_test")
            .withPassword("analysis_pack_test_password");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private AnalysisConfigurationService service;
    private AnalyticsPropertyDefinitionService propertyDefinitionService;
    private AnalyticsPropertyFilterService propertyFilterService;
    private AdminDashboardService dashboardService;

    @BeforeEach
    void setUp() {
        DataSource rootDataSource = dataSource(POSTGRES.getJdbcUrl());
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        root.execute("DROP SCHEMA IF EXISTS " + SYSTEM_SCHEMA + " CASCADE");
        root.execute("CREATE SCHEMA " + SYSTEM_SCHEMA);

        dataSource = dataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + SYSTEM_SCHEMA + ",public");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(SYSTEM_SCHEMA)
                .schemas(SYSTEM_SCHEMA)
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                INSERT INTO analytics_projects
                    (project_id, project_name, db_host, db_port, db_name, db_schema, db_user,
                     table_prefix, is_active)
                VALUES (?, 'Analysis Pack Project', 'localhost', 5432, 'project_db', 'analytics',
                        'project_user', '', TRUE)
                """, PROJECT_ID);

        objectMapper = JsonMapper.builder().build();
        AnalysisPackOwnershipService ownershipService = new AnalysisPackOwnershipService(
                jdbcTemplate, objectMapper
        );
        propertyDefinitionService = new AnalyticsPropertyDefinitionService(
                jdbcTemplate, objectMapper, ownershipService
        );
        propertyFilterService = new AnalyticsPropertyFilterService(
                objectMapper, propertyDefinitionService
        );
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases(eq(PROJECT_ID), anyList())).thenAnswer(invocation -> {
            Map<String, List<String>> result = new java.util.LinkedHashMap<>();
            for (String key : invocation.<List<String>>getArgument(1)) result.put(key, List.of(key));
            return result;
        });
        service = new AnalysisConfigurationService(
                jdbcTemplate, objectMapper, propertyDefinitionService, propertyFilterService, semantics,
                ownershipService, new AnalyticsMetricDependencyService(jdbcTemplate)
        );
        AnalyticsProject project = new AnalyticsProject();
        project.setProjectId(PROJECT_ID);
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        when(projectMapper.selectOne(any())).thenReturn(project);
        dashboardService = new AdminDashboardService(
                projectMapper, jdbcTemplate, objectMapper, new DashboardDefinitionValidator(),
                new DashboardOverviewMetricPolicy(mock(SemanticDictionaryService.class)),
                new DashboardCounterPolicy(mock(CounterService.class)),
                new DashboardGovernedMetricPolicy(service), ownershipService
        );
    }

    @Test
    void newerPackVersionDeactivatesDefinitionsRemovedFromItsAuthoritativeManifest() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", "opens"))
        ));
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(2, manifest(null, null))
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_property_definitions WHERE project_id = ? AND property_key = ?",
                Boolean.class, PROJECT_ID, "channel"
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_metric_definitions WHERE project_id = ? AND metric_key = ?",
                Boolean.class, PROJECT_ID, "opens"
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isEqualTo(2);
    }

    @Test
    void packCannotDeactivateMetricReferencedByAnActiveDashboard() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", "opens"))
        ));
        jdbcTemplate.update("""
                INSERT INTO analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition, is_active)
                VALUES (?, 'operations', '{"en":"Operations"}'::jsonb, 2,
                        '{"schemaVersion":2,"widgets":[{"id":"opens","type":"core.governedMetric","config":{"metricKey":"opens"}}]}'::jsonb,
                        TRUE)
                """, PROJECT_ID);

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(2, manifest("channel", null))
        ))).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("ANALYTICS_METRIC_IN_USE");
            assertThat(exception.getDetails().get("metricKeys")).isEqualTo(List.of("opens"));
            assertThat(exception.getDetails().get("dashboardKeys")).isEqualTo(List.of("operations"));
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_metric_definitions WHERE project_id = ? AND metric_key = ?",
                Boolean.class, PROJECT_ID, "opens"
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isEqualTo(1);
    }

    @Test
    void inactiveDashboardCannotBeReactivatedAfterItsMetricIsDeactivated() throws Exception {
        AnalyticsMetricDefinitionRequest activeMetric = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Lifecycle metric"), AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"), null, true
        );
        inTransaction(() -> service.upsertMetric(PROJECT_ID, "lifecycle.metric", activeMetric));
        Map<String, Object> definition = Map.of(
                "schemaVersion", 2,
                "widgets", List.of(Map.of(
                        "id", "lifecycle", "type", "core.governedMetric",
                        "layout", Map.of("x", 0, "y", 0, "w", 6, "h", 6),
                        "config", Map.of("metricKey", "lifecycle.metric")
                ))
        );
        AdminDashboardUpsertRequest inactiveDashboard = new AdminDashboardUpsertRequest(
                Map.of("en", "Lifecycle dashboard"), null, 2,
                definition, null, false, false
        );
        inTransaction(() -> dashboardService.upsert(PROJECT_ID, "lifecycle", inactiveDashboard));

        AnalyticsMetricDefinitionRequest inactiveMetric = new AnalyticsMetricDefinitionRequest(
                activeMetric.displayName(), activeMetric.metricType(), activeMetric.definition(), null, false
        );
        inTransaction(() -> service.upsertMetric(PROJECT_ID, "lifecycle.metric", inactiveMetric));
        AdminDashboardUpsertRequest reactivation = new AdminDashboardUpsertRequest(
                inactiveDashboard.displayName(), null, 2,
                definition, 1L, false, true
        );

        assertThatThrownBy(() -> inTransaction(() -> dashboardService.upsert(
                PROJECT_ID, "lifecycle", reactivation
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("DASHBOARD_GOVERNED_METRIC_UNAVAILABLE")
        );
        assertThat(dashboardService.get(PROJECT_ID, "lifecycle").isActive()).isFalse();
    }

    @Test
    void concurrentDashboardReferenceAndMetricDeactivationNeverCommitAnInvalidPair() throws Exception {
        AnalyticsMetricDefinitionRequest activeMetric = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Race metric"), AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"), null, true
        );
        inTransaction(() -> service.upsertMetric(PROJECT_ID, "race.metric", activeMetric));
        AnalyticsMetricDefinitionRequest inactiveMetric = new AnalyticsMetricDefinitionRequest(
                activeMetric.displayName(), activeMetric.metricType(), activeMetric.definition(), null, false
        );
        AdminDashboardUpsertRequest dashboard = new AdminDashboardUpsertRequest(
                Map.of("en", "Race dashboard"), null, 2,
                Map.of(
                        "schemaVersion", 2,
                        "widgets", List.of(Map.of(
                                "id", "race", "type", "core.governedMetric",
                                "layout", Map.of("x", 0, "y", 0, "w", 6, "h", 6),
                                "config", Map.of("metricKey", "race.metric")
                        ))
                ),
                null, false, true
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> dashboardAttempt = executor.submit(() -> raceAttempt(ready, start, () ->
                    dashboardService.upsert(PROJECT_ID, "race", dashboard)
            ));
            Future<String> deactivationAttempt = executor.submit(() -> raceAttempt(ready, start, () ->
                    service.upsertMetric(PROJECT_ID, "race.metric", inactiveMetric)
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = List.of(
                    dashboardAttempt.get(10, TimeUnit.SECONDS),
                    deactivationAttempt.get(10, TimeUnit.SECONDS)
            );
            assertThat(outcomes).filteredOn("success"::equals).hasSize(1);
            assertThat(outcomes).anyMatch(code -> Set.of(
                    "ANALYTICS_METRIC_IN_USE", "DASHBOARD_GOVERNED_METRIC_UNAVAILABLE"
            ).contains(code));
        }

        boolean metricActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_metric_definitions WHERE project_id = ? AND metric_key = ?",
                Boolean.class, PROJECT_ID, "race.metric"
        );
        int activeDashboardCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_dashboards WHERE project_id = ? AND dashboard_key = 'race' AND is_active = TRUE",
                Integer.class, PROJECT_ID
        );
        assertThat((metricActive && activeDashboardCount == 1)
                || (!metricActive && activeDashboardCount == 0)).isTrue();
    }

    @Test
    void packUpdateRequiresExplicitConfirmationBeforeDeactivatingOwnedDefinitions() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", "opens"))
        ));
        AnalysisPackImportRequest unconfirmed = new AnalysisPackImportRequest(
                2, Map.of("en", "Product baseline"), manifest(null, null), false
        );

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", unconfirmed
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                {
                    assertThat(exception.getCode())
                            .isEqualTo("ANALYSIS_PACK_DEACTIVATION_CONFIRMATION_REQUIRED");
                    assertThat(exception.getDetails().get("removedPropertyKeys"))
                            .isEqualTo(java.util.List.of("channel"));
                    assertThat(exception.getDetails().get("removedMetricKeys"))
                            .isEqualTo(java.util.List.of("opens"));
                    assertThat(exception.getDetails().get("removesTrustedSchemaPolicy"))
                            .isEqualTo(false);
                }
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_metric_definitions WHERE project_id = ? AND metric_key = ?",
                Boolean.class, PROJECT_ID, "opens"
        )).isTrue();
    }

    @Test
    void importedPackCanBeReloadedAsTheNextVersionRecoverySource() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", "opens"))
        ));

        assertThat(service.listAnalysisPacks(PROJECT_ID)).singleElement().satisfies(pack -> {
            assertThat(pack.packKey()).isEqualTo("product.baseline");
            assertThat(pack.packVersion()).isEqualTo(1);
            assertThat(pack.manifest().path("properties").get(0).path("propertyKey").asString())
                    .isEqualTo("channel");
            assertThat(pack.manifest().path("metrics").get(0).path("metricKey").asString())
                    .isEqualTo("opens");
            assertThat(pack.checksumSha256()).hasSize(64);
            assertThat(pack.versions()).singleElement().satisfies(version -> {
                assertThat(version.packVersion()).isEqualTo(1);
                assertThat(version.manifest()).isEqualTo(pack.manifest());
                assertThat(version.checksumSha256()).isEqualTo(pack.checksumSha256());
            });
        });
    }

    @Test
    void priorPackSnapshotRemainsReloadableAfterAConfirmedReplacement() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", "opens"))
        ));
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(2, manifest(null, null))
        ));

        assertThat(service.listAnalysisPacks(PROJECT_ID)).singleElement().satisfies(pack -> {
            assertThat(pack.packVersion()).isEqualTo(2);
            assertThat(pack.versions()).extracting(AnalysisPackVersionSnapshot::packVersion)
                    .containsExactly(2, 1);
            assertThat(pack.versions().get(1).manifest().path("properties").get(0)
                    .path("propertyKey").asString()).isEqualTo("channel");
        });
    }

    @Test
    void samePackVersionCannotChangeOnlyItsDisplayName() {
        JsonNode manifest = manifest("channel", "opens");
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest)
        ));
        AnalysisPackImportRequest renamed = new AnalysisPackImportRequest(
                1, Map.of("en", "Renamed baseline"), manifest, true
        );

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", renamed
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION")
        );

        assertThat(service.listAnalysisPacks(PROJECT_ID)).singleElement().satisfies(pack -> {
            assertThat(pack.displayName()).containsEntry("en", "Product baseline");
            assertThat(pack.versions()).singleElement().satisfies(version ->
                    assertThat(version.displayName()).containsEntry("en", "Product baseline")
            );
        });
    }

    @Test
    void identicalSameVersionRetryDoesNotMutatePackOrDuplicateAudit() {
        AnalysisPackImportRequest request = request(1, manifest("channel", "opens"));
        var first = new java.util.concurrent.atomic.AtomicReference<com.github.analyticshub.dto.AnalysisPackResponse>();
        var retry = new java.util.concurrent.atomic.AtomicReference<com.github.analyticshub.dto.AnalysisPackResponse>();
        inTransaction(() -> first.set(service.importPack(PROJECT_ID, "product.baseline", request)));
        inTransaction(() -> retry.set(service.importPack(PROJECT_ID, "product.baseline", request)));

        assertThat(retry.get().updatedAt()).isEqualTo(first.get().updatedAt());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM analytics_analysis_pack_audits
                 WHERE project_id = ? AND pack_key = ? AND pack_version = 1
                """, Integer.class, PROJECT_ID, "product.baseline")).isEqualTo(1);
    }

    @Test
    void trustedSchemaPolicyRejectsImplicitCrossVersionMetricsAndAllowsAuditedException() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifestWithTrustedSchemaPolicy())
        ));
        AnalyticsMetricDefinitionRequest unscoped = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Unscoped opens"),
                AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("{\"semanticEvent\":\"app.open\",\"propertyFilters\":[]}"),
                null,
                true
        );
        assertThatThrownBy(() -> inTransaction(() -> service.upsertMetric(
                PROJECT_ID, "unscoped.opens", unscoped
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("trustedSchemaPolicy");

        AnalyticsMetricDefinitionRequest verifiedCrossVersion = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Verified cross-version opens"),
                AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("""
                        {
                          "semanticEvent": "app.open",
                          "schemaScope": "CROSS_VERSION_VERIFIED",
                          "schemaScopeReason": "The event contract is verified across supported versions."
                        }
                        """),
                null,
                true
        );
        inTransaction(() -> service.upsertMetric(
                PROJECT_ID, "verified.opens", verifiedCrossVersion
        ));
        assertThat(service.getMetric(PROJECT_ID, "verified.opens").active()).isTrue();
    }

    @Test
    void crossVersionScopeAlwaysRequiresAnAuditableReason() {
        AnalyticsMetricDefinitionRequest missingReason = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Unaudited cross-version opens"),
                AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("""
                        {"semanticEvent":"app.open","schemaScope":"CROSS_VERSION_VERIFIED"}
                        """),
                null,
                true
        );

        assertThatThrownBy(() -> inTransaction(() -> service.upsertMetric(
                PROJECT_ID, "unaudited.opens", missingReason
        ))).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION");
            assertThat(exception.getMessage()).contains("schemaScopeReason");
        });
    }

    @Test
    void manualPropertyUpdateCannotInvalidatePackTrustedSchemaPolicy() {
        inTransaction(() -> propertyDefinitionService.upsert(
                PROJECT_ID,
                "event_schema_version",
                propertyRequest(AnalyticsPropertyDataType.STRING, List.of("3"))
        ));
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        var policy = manifest.putObject("trustedSchemaPolicy");
        policy.put("propertyKey", "event_schema_version");
        policy.putArray("trustedValues").add("3");
        var marker = manifest.putArray("properties").addObject();
        marker.put("propertyKey", "pack_marker");
        marker.set("displayName", objectMapper.valueToTree(Map.of("en", "Pack marker")));
        marker.put("dataType", "STRING");
        marker.put("filterable", false);
        marker.put("groupable", false);
        marker.put("journeyKey", false);
        marker.put("sensitive", false);
        marker.put("active", true);
        manifest.putArray("metrics");
        inTransaction(() -> service.importPack(
                PROJECT_ID, "external.schema.policy", request(1, manifest)
        ));

        assertThatThrownBy(() -> inTransaction(() -> propertyDefinitionService.upsert(
                PROJECT_ID,
                "event_schema_version",
                propertyRequest(AnalyticsPropertyDataType.STRING, List.of("4"))
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYSIS_PACK_TRUSTED_SCHEMA_CONFLICT")
        );
        assertThat(propertyDefinitionService.list(PROJECT_ID).items())
                .filteredOn(item -> item.propertyKey().equals("event_schema_version"))
                .singleElement()
                .satisfies(item -> assertThat(item.allowedValues()).containsExactly("3"));
    }

    @Test
    void importingTrustedSchemaPolicyRollsBackWhenAnExistingActiveMetricIsUnscoped() {
        AnalyticsMetricDefinitionRequest existingMetric = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Legacy opens"),
                AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("{\"semanticEvent\":\"app.open\",\"propertyFilters\":[]}"),
                null,
                true
        );
        inTransaction(() -> service.upsertMetric(PROJECT_ID, "legacy.opens", existingMetric));

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifestWithTrustedSchemaPolicy())
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("trustedSchemaPolicy");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isZero();
        assertThat(service.getMetric(PROJECT_ID, "legacy.opens").active()).isTrue();
        assertThat(service.getTrustedSchemaPolicy(PROJECT_ID)).isNull();
    }

    @Test
    void packUpgradeRollsBackWhenAMetricStillReferencesARemovedProperty() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("channel", null))
        ));

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID,
                "product.baseline",
                request(2, manifestWithMetricUsingRemovedProperty())
        ))).isInstanceOf(BusinessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_property_definitions WHERE project_id = ? AND property_key = ?",
                Boolean.class, PROJECT_ID, "channel"
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isEqualTo(1);
    }

    @Test
    void packCannotRemoveAPropertyUsedByAnotherActivePackMetric() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "properties.baseline", request(1, manifest("channel", null))
        ));
        inTransaction(() -> service.importPack(
                PROJECT_ID, "metrics.baseline", request(1, manifestWithMetricUsingRemovedProperty())
        ));

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "properties.baseline", request(2, manifest(null, null))
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("opens");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_property_definitions WHERE project_id = ? AND property_key = ?",
                Boolean.class, PROJECT_ID, "channel"
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "properties.baseline"
        )).isEqualTo(1);
    }

    @Test
    void samePackCanReduceAPropertyCapabilityWhenItsDependentMetricIsRemoved() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifestWithChannelContract(true, true))
        ));
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(2, manifestWithChannelContract(false, false))
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_filterable FROM analytics_property_definitions WHERE project_id = ? AND property_key = ?",
                Boolean.class, PROJECT_ID, "channel"
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM analytics_metric_definitions WHERE project_id = ? AND metric_key = ?",
                Boolean.class, PROJECT_ID, "opens"
        )).isFalse();
    }

    @Test
    void directEditsCannotDriftDefinitionsOwnedByAnActivePack() {
        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifestWithChannelContract(true, true))
        ));
        AnalyticsPropertyDefinitionRequest incompatible = new AnalyticsPropertyDefinitionRequest(
                Map.of("en", "Channel"),
                AnalyticsPropertyDataType.INTEGER,
                null,
                null,
                true,
                false,
                false,
                false,
                true
        );

        assertThatThrownBy(() -> inTransaction(() -> propertyDefinitionService.upsert(
                PROJECT_ID, "channel", incompatible
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Analysis Pack");

        AnalyticsMetricDefinitionRequest replacementMetric = new AnalyticsMetricDefinitionRequest(
                Map.of("en", "Changed opens"),
                AnalyticsMetricType.EVENT_COUNT,
                objectMapper.readTree("{\"semanticEvent\":\"open\"}"),
                "manual override",
                true
        );
        assertThatThrownBy(() -> inTransaction(() -> service.upsertMetric(
                PROJECT_ID, "opens", replacementMetric
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Analysis Pack");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT pack_version FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                Integer.class, PROJECT_ID, "product.baseline"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name ->> 'en' FROM analytics_metric_definitions "
                        + "WHERE project_id = ? AND metric_key = ?",
                String.class, PROJECT_ID, "opens"
        )).isEqualTo("Opens");
    }

    @Test
    void firstGovernedPackMustCoverLegacyDashboardPropertyReferencesAtomically() {
        jdbcTemplate.update("""
                INSERT INTO analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition, is_active)
                VALUES (?, 'legacy.funnel', '{"en":"Legacy funnel"}'::jsonb, 1,
                        '{"widgets":[{"type":"core.productFunnel","config":{"groupBy":"channel"}}]}'::jsonb,
                        TRUE)
                """, PROJECT_ID);

        assertThatThrownBy(() -> inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifest("unrelated", null))
        ))).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("ANALYTICS_GOVERNANCE_TRANSITION_BLOCKED");
            assertThat(exception.getMessage()).contains("legacy.funnel", "groupBy=channel");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_property_definitions WHERE project_id = ?",
                Integer.class,
                PROJECT_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_analysis_packs WHERE project_id = ?",
                Integer.class,
                PROJECT_ID
        )).isZero();

        inTransaction(() -> service.importPack(
                PROJECT_ID, "product.baseline", request(1, manifestWithGroupableChannel())
        ));
        assertThat(propertyDefinitionService.list(PROJECT_ID).items()).singleElement().satisfies(property -> {
            assertThat(property.propertyKey()).isEqualTo("channel");
            assertThat(property.groupable()).isTrue();
        });
    }

    @Test
    void typedAllowedValuesAndFiltersShareOneCanonicalRepresentation() {
        inTransaction(() -> {
            propertyDefinitionService.upsert(PROJECT_ID, "flag", propertyRequest(
                    AnalyticsPropertyDataType.BOOLEAN, List.of("TRUE")
            ));
            propertyDefinitionService.upsert(PROJECT_ID, "count", propertyRequest(
                    AnalyticsPropertyDataType.INTEGER, List.of("03")
            ));
            propertyDefinitionService.upsert(PROJECT_ID, "ratio", propertyRequest(
                    AnalyticsPropertyDataType.NUMBER, List.of("1.0")
            ));
        });

        Map<String, List<String>> allowedValues = new java.util.LinkedHashMap<>();
        propertyDefinitionService.list(PROJECT_ID).items().forEach(property ->
                allowedValues.put(property.propertyKey(), property.allowedValues())
        );
        assertThat(allowedValues)
                .containsEntry("flag", List.of("true"))
                .containsEntry("count", List.of("3"))
                .containsEntry("ratio", List.of("1"));

        AnalyticsPropertyFilterService.CompiledPropertyFilters filters = propertyFilterService.compile(
                PROJECT_ID,
                """
                        [
                          {"propertyKey":"flag","operator":"EQ","values":["TRUE"]},
                          {"propertyKey":"count","operator":"EQ","values":["03"]},
                          {"propertyKey":"ratio","operator":"EQ","values":["1.0"]}
                        ]
                        """,
                "properties"
        );
        assertThat(filters.arguments()).contains("true", "3", "1");
    }

    @Test
    void concurrentImportsOfTheSameVersionCannotCreateAMixedPackSnapshot() throws Exception {
        AnalysisPackImportRequest alpha = request(1, manifest("alpha", null));
        AnalysisPackImportRequest beta = request(1, manifest("beta", null));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> importAfterBarrier(alpha, ready, start));
            Future<Boolean> second = executor.submit(() -> importAfterBarrier(beta, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_property_definitions WHERE project_id = ? AND is_active = TRUE",
                Integer.class, PROJECT_ID
        )).isEqualTo(1);
        JsonNode storedManifest = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT manifest::text FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                String.class, PROJECT_ID, "product.baseline"
        ));
        String storedKey = storedManifest.path("properties").get(0).path("propertyKey").asString();
        assertThat(storedKey).isIn("alpha", "beta");
    }

    private boolean importAfterBarrier(
            AnalysisPackImportRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            inTransaction(() -> service.importPack(PROJECT_ID, "product.baseline", request));
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION");
            return false;
        }
    }

    private void inTransaction(Runnable operation) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> operation.run());
    }

    private String raceAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable operation
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            inTransaction(operation);
            return "success";
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }

    private AnalysisPackImportRequest request(int version, JsonNode manifest) {
        return new AnalysisPackImportRequest(version, Map.of("en", "Product baseline"), manifest, true);
    }

    private JsonNode manifest(String propertyKey, String metricKey) {
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        var properties = manifest.putArray("properties");
        if (propertyKey != null) {
            var property = properties.addObject();
            property.put("propertyKey", propertyKey);
            property.set("displayName", objectMapper.valueToTree(Map.of("en", propertyKey)));
            property.put("dataType", "STRING");
            property.put("filterable", true);
            property.put("groupable", false);
            property.put("journeyKey", false);
            property.put("sensitive", false);
            property.put("active", true);
        }
        var metrics = manifest.putArray("metrics");
        if (metricKey != null) {
            var metric = metrics.addObject();
            metric.put("metricKey", metricKey);
            metric.set("displayName", objectMapper.valueToTree(Map.of("en", metricKey)));
            metric.put("metricType", "EVENT_COUNT");
            metric.set("definition", objectMapper.readTree("{\"semanticEvent\":\"app.open\"}"));
            metric.put("active", true);
        }
        return manifest;
    }

    private JsonNode manifestWithMetricUsingRemovedProperty() {
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.putArray("properties");
        var metric = manifest.putArray("metrics").addObject();
        metric.put("metricKey", "opens");
        metric.set("displayName", objectMapper.valueToTree(Map.of("en", "Opens")));
        metric.put("metricType", "EVENT_COUNT");
        metric.set("definition", objectMapper.readTree("""
                {
                  "semanticEvent": "app.open",
                  "propertyFilters": [
                    {"propertyKey": "channel", "operator": "EQ", "values": ["web"]}
                  ]
                }
                """));
        metric.put("active", true);
        return manifest;
    }

    private JsonNode manifestWithChannelContract(boolean filterable, boolean includeMetric) {
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        var property = manifest.putArray("properties").addObject();
        property.put("propertyKey", "channel");
        property.set("displayName", objectMapper.valueToTree(Map.of("en", "Channel")));
        property.put("dataType", "STRING");
        property.put("filterable", filterable);
        property.put("groupable", false);
        property.put("journeyKey", false);
        property.put("sensitive", false);
        property.put("active", true);
        var metrics = manifest.putArray("metrics");
        if (includeMetric) {
            var source = manifestWithMetricUsingRemovedProperty().path("metrics").get(0);
            metrics.add(source.deepCopy());
        }
        return manifest;
    }

    private JsonNode manifestWithGroupableChannel() {
        var manifest = manifest("channel", null);
        ((tools.jackson.databind.node.ObjectNode) manifest.path("properties").get(0))
                .put("groupable", true);
        return manifest;
    }

    private JsonNode manifestWithTrustedSchemaPolicy() {
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        var policy = manifest.putObject("trustedSchemaPolicy");
        policy.put("propertyKey", "event_schema_version");
        policy.putArray("trustedValues").add("3");

        var property = manifest.putArray("properties").addObject();
        property.put("propertyKey", "event_schema_version");
        property.set("displayName", objectMapper.valueToTree(Map.of("en", "Event schema version")));
        property.put("dataType", "STRING");
        property.putArray("allowedValues").add("3");
        property.put("filterable", true);
        property.put("groupable", false);
        property.put("journeyKey", false);
        property.put("sensitive", false);
        property.put("active", true);

        var metric = manifest.putArray("metrics").addObject();
        metric.put("metricKey", "opens.v3");
        metric.set("displayName", objectMapper.valueToTree(Map.of("en", "V3 opens")));
        metric.put("metricType", "EVENT_COUNT");
        metric.set("definition", objectMapper.readTree("""
                {
                  "semanticEvent": "app.open",
                  "propertyFilters": [{
                    "propertyKey": "event_schema_version",
                    "operator": "EQ",
                    "values": ["3"]
                  }]
                }
                """));
        metric.put("active", true);
        return manifest;
    }

    private static AnalyticsPropertyDefinitionRequest propertyRequest(
            AnalyticsPropertyDataType dataType,
            List<String> allowedValues
    ) {
        return new AnalyticsPropertyDefinitionRequest(
                Map.of("en", "Typed property"),
                dataType,
                null,
                allowedValues,
                true,
                false,
                false,
                false,
                true
        );
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
