package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.SessionUploadRequest;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class SessionServicePostgresIT {

    private static final String PREFIX = "session_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("session_service_test")
            .withUsername("session_test")
            .withPassword("session_test_password");

    private String schema;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        schema = "session_it_" + SCHEMA_SEQUENCE.incrementAndGet();

        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        when(dataSourceManager.getTableName(anyString(), eq("sessions")))
                .thenReturn(quoted(PREFIX + "sessions"));

        sessionService = new SessionService(dataSourceManager, new ProjectTransactionExecutor());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void sameProjectAndDeviceCanUpdateTheirSession() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerDeviceId = UUID.randomUUID();
        setContext("project_one", ownerDeviceId);

        sessionService.uploadSession(request(sessionId, 10L, "original", 1, 2));
        sessionService.uploadSession(request(sessionId, 25L, "updated", 3, 4));

        Map<String, Object> row = sessionRow(sessionId);
        assertThat(sessionCount(sessionId)).isEqualTo(1);
        assertThat(row)
                .containsEntry("project_id", "project_one")
                .containsEntry("device_id", ownerDeviceId)
                .containsEntry("session_duration_ms", 25L)
                .containsEntry("device_model", "updated")
                .containsEntry("screen_count", 3)
                .containsEntry("event_count", 4);
    }

    @Test
    void anotherDeviceCannotOverwriteAnExistingSessionId() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerDeviceId = UUID.randomUUID();
        setContext("project_one", ownerDeviceId);
        sessionService.uploadSession(request(sessionId, 10L, "owner", 1, 2));

        setContext("project_one", UUID.randomUUID());

        assertConflict(() -> sessionService.uploadSession(request(sessionId, 99L, "attacker", 8, 9)));
        assertOwnerRowWasNotChanged(sessionId, ownerDeviceId);
    }

    @Test
    void anotherProjectCannotOverwriteAnExistingSessionId() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerDeviceId = UUID.randomUUID();
        setContext("project_one", ownerDeviceId);
        sessionService.uploadSession(request(sessionId, 10L, "owner", 1, 2));

        setContext("project_two", ownerDeviceId);

        assertConflict(() -> sessionService.uploadSession(request(sessionId, 99L, "attacker", 8, 9)));
        assertOwnerRowWasNotChanged(sessionId, ownerDeviceId);
    }

    private void assertConflict(Runnable upload) {
        assertThatThrownBy(upload::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("SESSION_ID_CONFLICT");
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private void assertOwnerRowWasNotChanged(UUID sessionId, UUID ownerDeviceId) {
        Map<String, Object> row = sessionRow(sessionId);
        assertThat(sessionCount(sessionId)).isEqualTo(1);
        assertThat(row)
                .containsEntry("project_id", "project_one")
                .containsEntry("device_id", ownerDeviceId)
                .containsEntry("session_duration_ms", 10L)
                .containsEntry("device_model", "owner")
                .containsEntry("screen_count", 1)
                .containsEntry("event_count", 2);
    }

    private void setContext(String projectId, UUID deviceId) {
        RequestContext context = new RequestContext();
        Device device = new Device();
        device.setDeviceId(deviceId);
        context.setProjectId(projectId);
        context.setDevice(device);
        context.setUserId("test-user");
        context.setDataSource(dataSource);
        RequestContext.set(context);
    }

    private SessionUploadRequest request(
            UUID sessionId,
            long duration,
            String deviceModel,
            int screenCount,
            int eventCount
    ) {
        return new SessionUploadRequest(
                sessionId,
                Instant.parse("2026-08-01T00:00:00Z"),
                duration,
                deviceModel,
                "test-os",
                "1.0.1",
                "101",
                screenCount,
                eventCount
        );
    }

    private Map<String, Object> sessionRow(UUID sessionId) {
        return jdbcTemplate.queryForMap(
                "SELECT project_id, device_id, session_duration_ms, device_model, screen_count, event_count " +
                        "FROM " + quoted(PREFIX + "sessions") + " WHERE session_id = ?::uuid",
                sessionId.toString()
        );
    }

    private long sessionCount(UUID sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoted(PREFIX + "sessions") + " WHERE session_id = ?::uuid",
                Long.class,
                sessionId.toString()
        );
        return count == null ? 0 : count;
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
