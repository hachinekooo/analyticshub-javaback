package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.dto.EventTrackResponse;
import com.github.analyticshub.entity.Device;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class EventServicePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("event_service_test")
            .withUsername("event_test")
            .withPassword("event_test_password");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private MultiDataSourceManager dataSourceManager;
    private CounterService counterService;
    private EventService eventService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("DROP TABLE IF EXISTS event_it_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS event_it_idempotency_keys");
        jdbcTemplate.execute("""
                CREATE TABLE event_it_events (
                    event_id VARCHAR(64) PRIMARY KEY,
                    device_id UUID NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id UUID,
                    event_type VARCHAR(100) NOT NULL CHECK (event_type <> 'force_failure'),
                    event_timestamp BIGINT NOT NULL,
                    properties JSONB,
                    project_id VARCHAR(50) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE event_it_idempotency_keys (
                    project_id VARCHAR(50) NOT NULL,
                    key_hash VARCHAR(64) NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    event_id VARCHAR(64) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (project_id, key_hash)
                )
                """);

        dataSourceManager = mock(MultiDataSourceManager.class);
        counterService = mock(CounterService.class);
        when(dataSourceManager.getTableName("test_project", "events")).thenReturn("event_it_events");
        when(dataSourceManager.getTableName("test_project", "idempotency_keys"))
                .thenReturn("event_it_idempotency_keys");
        eventService = new EventService(
                dataSourceManager,
                JsonMapper.builder().build(),
                counterService,
                new ProjectTransactionExecutor()
        );

        RequestContext context = new RequestContext();
        Device device = new Device();
        device.setDeviceId(UUID.randomUUID());
        context.setProjectId("test_project");
        context.setDevice(device);
        context.setUserId("test_user");
        context.setDataSource(dataSource);
        RequestContext.set(context);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void duplicateIdempotencyKeyWritesAndCountsOnlyOnce() {
        EventTrackRequest request = request("item_completed", "completion:item-1");

        EventTrackResponse first = eventService.trackEvent(request);
        EventTrackResponse duplicate = eventService.trackEvent(request);

        assertThat(duplicate.eventId()).isEqualTo(first.eventId());
        assertThat(count("event_it_events")).isEqualTo(1);
        assertThat(count("event_it_idempotency_keys")).isEqualTo(1);
        verify(counterService).processEventAutoIncrements(
                eq("test_project"), eq("item_completed"), eq(Map.of("status", "completed"))
        );
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedAndOriginalEventRemains() {
        EventTrackRequest first = request("item_completed", "completion:item-conflict");
        EventTrackResponse response = eventService.trackEvent(first);
        EventTrackRequest conflicting = new EventTrackRequest(
                "item_shared",
                first.timestamp(),
                Map.of("status", "shared"),
                null,
                first.idempotencyKey()
        );

        assertThatThrownBy(() -> eventService.trackEvent(conflicting))
                .isInstanceOfSatisfying(com.github.analyticshub.exception.BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(409);
                });

        assertThat(count("event_it_events")).isEqualTo(1);
        assertThat(count("event_it_idempotency_keys")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_id FROM event_it_events",
                String.class
        )).isEqualTo(response.eventId());
        verify(counterService, never()).processEventAutoIncrements(
                eq("test_project"),
                eq("item_shared"),
                any()
        );
    }

    @Test
    void propertyMapOrderDoesNotChangeIdempotencyFingerprint() {
        long timestamp = System.currentTimeMillis();
        EventTrackRequest first = new EventTrackRequest(
                "item_completed",
                timestamp,
                new java.util.LinkedHashMap<>(Map.of("a", 1, "b", 2)),
                null,
                "completion:ordered-map"
        );
        java.util.LinkedHashMap<String, Object> reversed = new java.util.LinkedHashMap<>();
        reversed.put("b", 2);
        reversed.put("a", 1);
        EventTrackRequest retry = new EventTrackRequest(
                first.eventType(),
                timestamp,
                reversed,
                null,
                first.idempotencyKey()
        );

        EventTrackResponse original = eventService.trackEvent(first);
        EventTrackResponse duplicate = eventService.trackEvent(retry);

        assertThat(duplicate.eventId()).isEqualTo(original.eventId());
        assertThat(count("event_it_events")).isEqualTo(1);
    }

    @Test
    void eventInsertFailureRollsBackIdempotencyReservation() {
        EventTrackRequest request = request("force_failure", "completion:item-2");

        assertThatThrownBy(() -> eventService.trackEvent(request)).isInstanceOf(RuntimeException.class);

        assertThat(count("event_it_events")).isZero();
        assertThat(count("event_it_idempotency_keys")).isZero();
        verify(counterService, never()).processEventAutoIncrements(any(), any(), any());
    }

    @Test
    void counterFailureRollsBackEventAndIdempotencyReservation() {
        doThrow(new IllegalStateException("counter unavailable"))
                .when(counterService)
                .processEventAutoIncrements(eq("test_project"), eq("item_completed"), any());

        assertThatThrownBy(() -> eventService.trackEvent(request("item_completed", "completion:item-3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("counter unavailable");

        assertThat(count("event_it_events")).isZero();
        assertThat(count("event_it_idempotency_keys")).isZero();
    }

    @Test
    void batchDerivationsOnlyRunForAcceptedEvents() {
        EventTrackRequest first = request("item_completed", "completion:item-4");
        EventTrackRequest duplicate = new EventTrackRequest(
                first.eventType(),
                first.timestamp(),
                first.properties(),
                first.sessionId(),
                first.idempotencyKey()
        );
        EventTrackRequest invalid = new EventTrackRequest(" ", System.currentTimeMillis(), null, null, null);
        EventTrackRequest second = request("item_shared", null);

        eventService.trackEventsBatch(new EventTrackRequest[]{first, duplicate, invalid, second});

        assertThat(count("event_it_events")).isEqualTo(2);
        assertThat(count("event_it_idempotency_keys")).isEqualTo(1);
        verify(counterService).processEventAutoIncrements(
                eq("test_project"), eq("item_completed"), eq(Map.of("status", "completed"))
        );
        verify(counterService).processEventAutoIncrements(
                eq("test_project"), eq("item_shared"), eq(Map.of("status", "completed"))
        );
        verify(counterService, never()).processEventAutoIncrements(eq("test_project"), eq(" "), any());
    }

    private EventTrackRequest request(String eventType, String idempotencyKey) {
        return new EventTrackRequest(
                eventType,
                System.currentTimeMillis(),
                Map.of("status", "completed"),
                null,
                idempotencyKey
        );
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
