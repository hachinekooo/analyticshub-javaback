package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class CounterServicePostgresIT {

    private static final String PROJECT_ID = "counter_project";
    private static final String PREFIX = "counter_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("counter_service_test")
            .withUsername("counter_test")
            .withPassword("counter_test_password");

    private String schema;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private CounterService counterService;
    private EventService eventService;

    @BeforeEach
    void setUp() {
        schema = "counter_it_" + SCHEMA_SEQUENCE.incrementAndGet();
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
                "Counter Test",
                "localhost",
                5432,
                "counter_service_test",
                schema,
                "counter_test",
                "counter_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "counters")).thenReturn(quoted(PREFIX + "counters"));
        when(dataSourceManager.getTableName(PROJECT_ID, "events")).thenReturn(quoted(PREFIX + "events"));
        when(dataSourceManager.getTableName(PROJECT_ID, "idempotency_keys"))
                .thenReturn(quoted(PREFIX + "idempotency_keys"));

        ObjectMapper objectMapper = JsonMapper.builder().build();
        ProjectTransactionExecutor transactions = new ProjectTransactionExecutor();
        counterService = new CounterService(dataSourceManager, objectMapper, transactions);
        eventService = new EventService(dataSourceManager, objectMapper, counterService, transactions);

        RequestContext context = new RequestContext();
        Device device = new Device();
        device.setDeviceId(UUID.randomUUID());
        context.setProjectId(PROJECT_ID);
        context.setDevice(device);
        context.setUserId("11111111-1111-4111-8111-111111111111");
        context.setDataSource(dataSource);
        RequestContext.set(context);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void metadataOnlyUpsertCreatesZeroAndIncrementPreservesConfiguration() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        CounterRecord created = counterService.upsert(
                PROJECT_ID,
                "tasks_completed",
                new CounterUpsertRequest(
                        null,
                        objectMapper.readTree("{\"zh-CN\":\"累计完成任务\"}"),
                        objectMapper.readTree("{\"zh-CN\":\"项\"}"),
                        objectMapper.readTree("{\"event_type\":\"task_completed\"}"),
                        true,
                        "累计完成的任务数量"
                )
        );

        CounterRecord incremented = counterService.increment(PROJECT_ID, "tasks_completed", 1);

        assertThat(created.value()).isZero();
        assertThat(incremented.value()).isEqualTo(1);
        assertThat(incremented.displayName().path("zh-CN").asString()).isEqualTo("累计完成任务");
        assertThat(incremented.eventTrigger().path("event_type").asString()).isEqualTo("task_completed");
        assertThat(incremented.isPublic()).isTrue();
    }

    @Test
    void explicitClearEventTriggerRemovesAutomationRule() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        counterService.upsert(
                PROJECT_ID,
                "manual_counter",
                new CounterUpsertRequest(
                        5L,
                        null,
                        null,
                        objectMapper.readTree("{\"event_type\":\"task_completed\"}"),
                        false,
                        false,
                        null
                )
        );

        CounterRecord cleared = counterService.upsert(
                PROJECT_ID,
                "manual_counter",
                new CounterUpsertRequest(null, null, null, null, true, false, null)
        );

        assertThat(cleared.eventTrigger()).isNull();
        assertThat(cleared.value()).isEqualTo(5L);
    }

    @Test
    void readDoesNotCreateADeletedCountersTable() {
        jdbcTemplate.execute("DROP TABLE " + quoted(PREFIX + "counters"));

        assertThatThrownBy(() -> counterService.list(PROJECT_ID, false))
                .isInstanceOf(DataAccessException.class);
        assertThat(tableExists(PREFIX + "counters")).isFalse();
    }

    @Test
    void eventAndEveryMatchingCounterCommitTogether() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        var trigger = objectMapper.readTree("{\"event_type\":\"task_completed\"}");
        counterService.upsert(
                PROJECT_ID,
                "tasks_completed",
                new CounterUpsertRequest(0L, null, null, trigger, true, null)
        );

        eventService.trackEvent(new EventTrackRequest(
                "task_completed",
                System.currentTimeMillis(),
                Map.of("source", "editor"),
                null,
                "task:one:completed"
        ));

        assertThat(counterService.get(PROJECT_ID, "tasks_completed", false).value()).isEqualTo(1);
        assertThat(count(PREFIX + "events")).isEqualTo(1);
        assertThat(count(PREFIX + "idempotency_keys")).isEqualTo(1);
    }

    @Test
    void anyCounterProjectionFailureRollsBackAllCountersAndEventFacts() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        var trigger = objectMapper.readTree("{\"event_type\":\"task_completed\"}");
        counterService.upsert(
                PROJECT_ID,
                "a_normal_counter",
                new CounterUpsertRequest(0L, null, null, trigger, false, null)
        );
        counterService.upsert(
                PROJECT_ID,
                "z_overflow_counter",
                new CounterUpsertRequest(Long.MAX_VALUE, null, null, trigger, false, null)
        );

        assertThatThrownBy(() -> eventService.trackEvent(new EventTrackRequest(
                "task_completed",
                System.currentTimeMillis(),
                null,
                null,
                "task:overflow:completed"
        ))).isInstanceOf(DataAccessException.class);

        assertThat(counterService.get(PROJECT_ID, "a_normal_counter", false).value()).isZero();
        assertThat(counterService.get(PROJECT_ID, "z_overflow_counter", false).value())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(count(PREFIX + "events")).isZero();
        assertThat(count(PREFIX + "idempotency_keys")).isZero();
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                Boolean.class,
                schema,
                table
        );
        return Boolean.TRUE.equals(exists);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + quoted(table), Long.class);
        return value == null ? 0 : value;
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
