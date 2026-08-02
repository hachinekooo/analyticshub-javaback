package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.CounterHistoryMode;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@Testcontainers
class CounterRebuildPostgresIT {

    private static final String PROJECT_ID = "rebuild_project";
    private static final String PREFIX = "rebuild_";
    private static final long REBUILD_GATE_LOCK = 9_130_421L;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("counter_rebuild_test")
            .withUsername("rebuild_test")
            .withPassword("rebuild_test_password");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private String schema;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private CounterService counterService;
    private EventService eventService;
    private SemanticDictionaryService semantics;

    @BeforeEach
    void setUp() {
        schema = "counter_rebuild_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig projectConfig = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Counter Rebuild Test",
                "localhost",
                5432,
                "counter_rebuild_test",
                schema,
                "rebuild_test",
                "rebuild_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "counters"))
                .thenReturn(quoted(PREFIX + "counters"));
        when(dataSourceManager.getTableName(PROJECT_ID, "events"))
                .thenReturn(quoted(PREFIX + "events"));
        when(dataSourceManager.getTableName(PROJECT_ID, "idempotency_keys"))
                .thenReturn(quoted(PREFIX + "idempotency_keys"));

        ProjectTransactionExecutor transactions = new ProjectTransactionExecutor();
        semantics = identitySemantics();
        counterService = new CounterService(dataSourceManager, objectMapper, transactions, semantics);
        eventService = new EventService(dataSourceManager, objectMapper, counterService, transactions);
        RequestContext.set(requestContext());
    }

    private SemanticDictionaryService identitySemantics() {
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases(eq(PROJECT_ID), anyList())).thenAnswer(invocation -> {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String key : invocation.<List<String>>getArgument(1)) result.put(key, List.of(key.strip()));
            return result;
        });
        when(semantics.resolveActiveEventSemanticKey(eq(PROJECT_ID), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return semantics;
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void rebuildReplacesCounterWithAllMatchingHistoricalEventsAndRecordsMetadata() throws Exception {
        counterService.upsert(
                PROJECT_ID,
                "completed_items",
                new CounterUpsertRequest(
                        99L,
                        null,
                        null,
                        objectMapper.readTree("{\"semantic_key\":\"item_completed\"}"),
                        false,
                        null
                )
        );
        insertEvent(PROJECT_ID, "item_completed", "{\"source\":\"api\"}");
        insertEvent(PROJECT_ID, "item_completed", "{\"source\":\"import\"}");
        insertEvent(PROJECT_ID, "item_created", "{\"source\":\"api\"}");
        insertEvent("another_project", "item_completed", "{\"source\":\"api\"}");

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "completed_items");

        assertThat(rebuilt.value()).isEqualTo(2);
        assertThat(rebuilt.lastRebuiltAt()).isNotBlank();
        assertThat(rebuilt.lastRebuildEventCount()).isEqualTo(2);
    }

    @Test
    void rebuildAddsPersistentBusinessOffsetToHistoricalEvents() throws Exception {
        insertEvent(PROJECT_ID, "item_completed", "{}");
        insertEvent(PROJECT_ID, "item_completed", "{}");
        counterService.upsert(
                PROJECT_ID,
                "migrated_total",
                new CounterUpsertRequest(
                        null,
                        null,
                        null,
                        objectMapper.readTree("{\"semantic_key\":\"item_completed\"}"),
                        false,
                        false,
                        null,
                        10L,
                        CounterHistoryMode.INCLUDE_EXISTING
                )
        );

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "migrated_total");

        assertThat(rebuilt.value()).isEqualTo(12L);
        assertThat(rebuilt.lastRebuildEventCount()).isEqualTo(2L);
        assertThat(rebuilt.rebuildOffset()).isEqualTo(10L);
        assertThat(rebuilt.historyMode()).isEqualTo(CounterHistoryMode.INCLUDE_EXISTING);
    }

    @Test
    void startFromNowExcludesEarlierEventsAndKeepsOffsetOnRebuild() throws Exception {
        insertEvent(PROJECT_ID, "item_completed", "{\"phase\":\"before\"}");
        CounterRecord configured = counterService.upsert(
                PROJECT_ID,
                "new_install_total",
                new CounterUpsertRequest(
                        null,
                        null,
                        null,
                        objectMapper.readTree("{\"semantic_key\":\"item_completed\"}"),
                        false,
                        false,
                        null,
                        5L,
                        CounterHistoryMode.START_FROM_NOW
                )
        );
        insertEvent(PROJECT_ID, "item_completed", "{\"phase\":\"after\"}");

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "new_install_total");

        assertThat(configured.eventCountStartAt()).isNotBlank();
        assertThat(rebuilt.value()).isEqualTo(6L);
        assertThat(rebuilt.lastRebuildEventCount()).isOne();
        assertThat(rebuilt.rebuildOffset()).isEqualTo(5L);
        assertThat(rebuilt.historyMode()).isEqualTo(CounterHistoryMode.START_FROM_NOW);
    }

    @Test
    void rebuildAndRealtimeProjectionSupportRenamedEventKeys() throws Exception {
        when(semantics.resolveActiveEventAliases(
                PROJECT_ID,
                List.of("core.action.completed")
        )).thenReturn(Map.of(
                "core.action.completed",
                List.of("item_completed", "item_done_v2")
        ));
        when(semantics.resolveActiveEventSemanticKey(PROJECT_ID, "item_completed"))
                .thenReturn("core.action.completed");
        when(semantics.resolveActiveEventSemanticKey(PROJECT_ID, "item_done_v2"))
                .thenReturn("core.action.completed");
        counterService.upsert(
                PROJECT_ID,
                "completed_items",
                new CounterUpsertRequest(
                        0L,
                        null,
                        null,
                        objectMapper.readTree("{\"semantic_key\":\"core.action.completed\"}"),
                        false,
                        null
                )
        );
        insertEvent(PROJECT_ID, "item_completed", "{}");
        insertEvent(PROJECT_ID, "item_done_v2", "{}");

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "completed_items");
        assertThat(rebuilt.value()).isEqualTo(2);

        eventService.trackEvent(new EventTrackRequest(
                "item_done_v2",
                System.currentTimeMillis(),
                Map.of(),
                null,
                "renamed:item:live"
        ));
        assertThat(counterService.get(PROJECT_ID, "completed_items", false).value()).isEqualTo(3);
    }

    @Test
    void rebuildAndRealtimeProjectionApplyAnyOfConditionsPerEventAlias() throws Exception {
        CounterRecord configured = counterService.upsert(
                PROJECT_ID,
                "qualified_completed_items",
                new CounterUpsertRequest(
                        0L,
                        null,
                        null,
                        objectMapper.readTree("""
                                {
                                  "any_of": [
                                    {"semantic_key": " item_completed ", "conditions": {}},
                                    {
                                      "semantic_key": "item_done_v2",
                                      "conditions": {"status": "success"}
                                    },
                                    {
                                      "semantic_key": "item_done_v2",
                                      "conditions": {"status": "recovered"}
                                    }
                                  ]
                                }
                                """),
                        false,
                        null
                )
        );
        assertThat(configured.eventTrigger()).isEqualTo(objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": "item_completed"},
                    {
                      "semantic_key": "item_done_v2",
                      "conditions": {"status": "success"}
                    },
                    {
                      "semantic_key": "item_done_v2",
                      "conditions": {"status": "recovered"}
                    }
                  ]
                }
                """));
        insertEvent(PROJECT_ID, "item_completed", "{}");
        insertEvent(PROJECT_ID, "item_done_v2", "{\"status\":\"success\"}");
        insertEvent(PROJECT_ID, "item_done_v2", "{\"status\":\"recovered\"}");
        insertEvent(PROJECT_ID, "item_done_v2", "{\"status\":\"failed\"}");
        insertEvent(PROJECT_ID, "item_created", "{\"status\":\"success\"}");

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "qualified_completed_items");
        assertThat(rebuilt.value()).isEqualTo(3);
        assertThat(rebuilt.lastRebuildEventCount()).isEqualTo(3);

        eventService.trackEvent(new EventTrackRequest(
                "item_completed",
                System.currentTimeMillis(),
                Map.of(),
                null,
                "any-of:legacy:live"
        ));
        eventService.trackEvent(new EventTrackRequest(
                "item_done_v2",
                System.currentTimeMillis(),
                Map.of("status", "success"),
                null,
                "any-of:current:success"
        ));
        eventService.trackEvent(new EventTrackRequest(
                "item_done_v2",
                System.currentTimeMillis(),
                Map.of("status", "failed"),
                null,
                "any-of:current:failed"
        ));
        eventService.trackEvent(new EventTrackRequest(
                "item_done_v2",
                System.currentTimeMillis(),
                Map.of("status", "recovered"),
                null,
                "any-of:current:recovered"
        ));

        assertThat(counterService.get(PROJECT_ID, "qualified_completed_items", false).value())
                .isEqualTo(6);
    }

    @Test
    void rebuildUsesJsonbContainmentForNestedAndArrayConditions() throws Exception {
        counterService.upsert(
                PROJECT_ID,
                "qualified_items",
                new CounterUpsertRequest(
                        0L,
                        null,
                        null,
                        objectMapper.readTree("""
                                {
                                  "semantic_key": "item_completed",
                                  "conditions": {
                                    "status": "done",
                                    "channel": {"kind": "api"},
                                    "tags": ["stable"]
                                  }
                                }
                                """),
                        false,
                        null
                )
        );
        insertEvent(
                PROJECT_ID,
                "item_completed",
                "{\"status\":\"done\",\"channel\":{\"kind\":\"api\",\"version\":2},\"tags\":[\"stable\",\"paid\"]}"
        );
        insertEvent(
                PROJECT_ID,
                "item_completed",
                "{\"status\":\"pending\",\"channel\":{\"kind\":\"api\"},\"tags\":[\"stable\"]}"
        );
        insertEvent(
                PROJECT_ID,
                "item_completed",
                "{\"status\":\"done\",\"channel\":{\"kind\":\"web\"},\"tags\":[\"stable\"]}"
        );

        CounterRecord rebuilt = counterService.rebuild(PROJECT_ID, "qualified_items");

        assertThat(rebuilt.value()).isOne();
        assertThat(rebuilt.lastRebuildEventCount()).isOne();
    }

    @Test
    void rebuildWithoutEventRuleIsRejectedWithStableBadRequest() {
        counterService.upsert(
                PROJECT_ID,
                "manual_total",
                new CounterUpsertRequest(12L, null, null, null, false, null)
        );

        assertThatThrownBy(() -> counterService.rebuild(PROJECT_ID, "manual_total"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("COUNTER_REBUILD_RULE_REQUIRED");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(400);
                });
    }

    @Test
    void upsertRejectsMalformedOrAmbiguousEventRules() throws Exception {
        var unknownField = objectMapper.readTree(
                "{\"semantic_key\":\"item_completed\",\"increment_by\":2}"
        );
        var invalidConditions = objectMapper.readTree(
                "{\"semantic_key\":\"item_completed\",\"conditions\":[\"done\"]}"
        );
        var blankEventType = objectMapper.readTree("{\"semantic_key\":\"   \"}");
        var mixedAnyOfAndLegacy = objectMapper.readTree("""
                {
                  "semantic_key": "item_completed",
                  "any_of": [{"semantic_key": "item_done_v2"}]
                }
                """);
        var emptyAnyOf = objectMapper.readTree("{\"any_of\":[]}");
        var clauseWithUnknownField = objectMapper.readTree("""
                {"any_of":[{"semantic_key":"item_completed","increment_by":2}]}
                """);
        var clauseWithEventTypes = objectMapper.readTree("""
                {"any_of":[{"semantic_keys":["item_completed"]}]}
                """);
        var clauseWithInvalidConditions = objectMapper.readTree("""
                {"any_of":[{"semantic_key":"item_completed","conditions":["done"]}]}
                """);

        for (var invalidRule : new tools.jackson.databind.JsonNode[]{
                unknownField,
                invalidConditions,
                blankEventType,
                mixedAnyOfAndLegacy,
                emptyAnyOf,
                clauseWithUnknownField,
                clauseWithEventTypes,
                clauseWithInvalidConditions
        }) {
            assertThatThrownBy(() -> counterService.upsert(
                    PROJECT_ID,
                    "invalid_rule",
                    new CounterUpsertRequest(null, null, null, invalidRule, false, null)
            )).isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo("INVALID_COUNTER_EVENT_TRIGGER");
                assertThat(exception.getHttpStatus().value()).isEqualTo(400);
            });
        }
        assertThat(count(PREFIX + "counters")).isZero();
    }

    @Test
    void realtimeEventWaitsForRebuildRowLockAndItsIncrementIsNotLost() throws Exception {
        counterService.upsert(
                PROJECT_ID,
                "completed_items",
                new CounterUpsertRequest(
                        50L,
                        null,
                        null,
                        objectMapper.readTree("{\"semantic_key\":\"item_completed\"}"),
                        false,
                        null
                )
        );
        insertEvent(PROJECT_ID, "item_completed", "{\"source\":\"history\"}");
        insertEvent(PROJECT_ID, "item_completed", "{\"source\":\"history\"}");
        installRebuildGateTrigger();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection gateConnection = dataSource.getConnection();
             PreparedStatement lock = gateConnection.prepareStatement("SELECT pg_advisory_lock(?)");
             PreparedStatement unlock = gateConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            lock.setLong(1, REBUILD_GATE_LOCK);
            lock.execute();

            Future<CounterRecord> rebuild = executor.submit(
                    () -> counterService.rebuild(PROJECT_ID, "completed_items")
            );
            awaitBlockedAdvisoryLock();

            Future<?> realtimeEvent = executor.submit(() -> {
                RequestContext.set(requestContext());
                try {
                    return eventService.trackEvent(new EventTrackRequest(
                            "item_completed",
                            System.currentTimeMillis(),
                            Map.of("source", "live"),
                            null,
                            "live:item:1"
                    ));
                } finally {
                    RequestContext.clear();
                }
            });

            TimeUnit.MILLISECONDS.sleep(150);
            assertThat(rebuild).isNotDone();
            assertThat(realtimeEvent).isNotDone();

            unlock.setLong(1, REBUILD_GATE_LOCK);
            unlock.execute();

            assertThat(rebuild.get(10, TimeUnit.SECONDS).value()).isEqualTo(2);
            realtimeEvent.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        CounterRecord finalCounter = counterService.get(PROJECT_ID, "completed_items", false);
        assertThat(finalCounter.value()).isEqualTo(3);
        assertThat(finalCounter.lastRebuildEventCount()).isEqualTo(2);
        assertThat(count(PREFIX + "events")).isEqualTo(3);
    }

    private void installRebuildGateTrigger() {
        jdbcTemplate.execute(String.format("""
                CREATE FUNCTION %s.counter_rebuild_gate() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.last_rebuilt_at IS DISTINCT FROM OLD.last_rebuilt_at THEN
                        PERFORM pg_advisory_xact_lock(%d);
                    END IF;
                    RETURN NEW;
                END;
                $$
                """, quoted(schema), REBUILD_GATE_LOCK));
        jdbcTemplate.execute(String.format("""
                CREATE TRIGGER counter_rebuild_gate
                BEFORE UPDATE ON %s
                FOR EACH ROW EXECUTE FUNCTION %s.counter_rebuild_gate()
                """, quoted(PREFIX + "counters"), quoted(schema)));
    }

    private void awaitBlockedAdvisoryLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                    Long.class
            );
            if (waiting != null && waiting > 0) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("rebuild did not reach the guarded counter update");
    }

    private void insertEvent(String projectId, String eventType, String propertiesJson) {
        String sql = String.format("""
                INSERT INTO %s
                    (event_id, device_id, user_id, event_type, event_timestamp, properties, project_id)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)
                """, quoted(PREFIX + "events"));
        jdbcTemplate.update(
                sql,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "test_user",
                eventType,
                System.currentTimeMillis(),
                propertiesJson,
                projectId
        );
    }

    private RequestContext requestContext() {
        RequestContext context = new RequestContext();
        Device device = new Device();
        device.setDeviceId(UUID.randomUUID());
        context.setProjectId(PROJECT_ID);
        context.setDevice(device);
        context.setUserId("test_user");
        context.setDataSource(dataSource);
        return context;
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + quoted(table), Long.class);
        return value == null ? 0L : value;
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
