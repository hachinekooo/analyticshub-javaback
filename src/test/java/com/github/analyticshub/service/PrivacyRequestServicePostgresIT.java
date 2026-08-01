package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestCreatedResponse;
import com.github.analyticshub.dto.PrivacyRequestSubmitRequest;
import com.github.analyticshub.dto.PrivacyRequestStatusResponse;
import com.github.analyticshub.dto.PrivacyRequestType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class PrivacyRequestServicePostgresIT {

    private static final String PROJECT_ID = "privacy_project";
    private static final String USER_ID = "11111111-1111-4111-8111-111111111111";
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String CONTACT_EMAIL = "user@example.com";
    private static final String PREFIX = "privacy_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("privacy_service_test")
            .withUsername("privacy_test")
            .withPassword("privacy_test_password");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private EmailService emailService;
    private PrivacyRequestService privacyRequestService;

    @BeforeEach
    void setUp() {
        String schema = "privacy_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        when(dataSourceManager.getTableName(PROJECT_ID, "privacy_requests"))
                .thenReturn(quoted(PREFIX + "privacy_requests"));

        emailService = mock(EmailService.class);
        privacyRequestService = new PrivacyRequestService(
                dataSourceManager,
                JsonMapper.builder().build(),
                emailService,
                new ProjectTransactionExecutor()
        );
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void concurrentEquivalentSubmissionsCreateOneRequestAndReuseIt() throws Exception {
        String lockIdentity = PrivacyRequestService.duplicateLockIdentity(
                PROJECT_ID,
                USER_ID,
                DEVICE_ID.toString(),
                PrivacyRequestType.EXPORT,
                PrivacyProcessor.ANALYTICSHUB,
                CONTACT_EMAIL
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection blockingConnection = dataSource.getConnection()) {
            blockingConnection.setAutoCommit(false);
            acquireTransactionLock(blockingConnection, lockIdentity);

            Future<PrivacyRequestCreatedResponse> first = executor.submit(() -> submitAfter(ready, start));
            Future<PrivacyRequestCreatedResponse> second = executor.submit(() -> submitAfter(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(waitForAdvisoryLockWaiters(2)).isTrue();

            blockingConnection.commit();

            PrivacyRequestCreatedResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            PrivacyRequestCreatedResponse secondResponse = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResponse.requestId()).isEqualTo(secondResponse.requestId());
            assertThat(List.of(firstResponse.message(), secondResponse.message())).containsExactlyInAnyOrder(
                    "请求已创建，后台将人工处理并通过邮件反馈结果",
                    "已有未处理的同类请求，后台将继续按原工单处理并通过邮件反馈结果"
            );
            assertThat(requestCount()).isEqualTo(1);
            verify(emailService, times(1)).sendPrivacyRequestSubmittedAlert(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    any()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void publicStatusIsDeviceScopedAndDoesNotExposeAdminOnlyFields() {
        setRequestContext();
        PrivacyRequestCreatedResponse created = privacyRequestService.submitDeleteRequest(
                new PrivacyRequestSubmitRequest(
                        CONTACT_EMAIL,
                        PrivacyProcessor.ANALYTICSHUB.name(),
                        "APP_SETTINGS",
                        "delete my data",
                        Map.of("client", "test")
                )
        );
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "privacy_requests") +
                        " SET operator = ?, operator_note = ?, result_payload = ?::jsonb WHERE request_id = ?",
                "internal-operator",
                "internal-only note",
                "{\"internalTicket\":\"secret\"}",
                created.requestId()
        );

        PrivacyRequestStatusResponse ownStatus = privacyRequestService.getRequest(created.requestId());
        var publicJson = JsonMapper.builder().build().valueToTree(ownStatus);
        assertThat(publicJson.path("requestId").asString()).isEqualTo(created.requestId());
        assertThat(publicJson.has("operator")).isFalse();
        assertThat(publicJson.has("operatorNote")).isFalse();
        assertThat(publicJson.has("resultPayload")).isFalse();
        assertThat(publicJson.has("metadata")).isFalse();
        assertThat(publicJson.has("userId")).isFalse();
        assertThat(publicJson.has("deviceId")).isFalse();

        setRequestContext(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        assertThatThrownBy(() -> privacyRequestService.getRequest(created.requestId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PRIVACY_REQUEST_NOT_FOUND");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(404);
                });
        assertThatThrownBy(privacyRequestService::getLatestRequest)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("PRIVACY_REQUEST_NOT_FOUND"));
    }

    private PrivacyRequestCreatedResponse submitAfter(CountDownLatch ready,
                                                      CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        setRequestContext();
        try {
            return privacyRequestService.submitExportRequest(new PrivacyRequestSubmitRequest(
                    CONTACT_EMAIL,
                    PrivacyProcessor.ANALYTICSHUB.name(),
                    "APP_SETTINGS",
                    "export my data",
                    Map.of("appVersion", "1.0.1")
            ));
        } finally {
            RequestContext.clear();
        }
    }

    private void setRequestContext() {
        setRequestContext(DEVICE_ID);
    }

    private void setRequestContext(UUID deviceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);

        RequestContext context = new RequestContext();
        context.setProjectId(PROJECT_ID);
        context.setUserId(USER_ID);
        context.setDevice(device);
        context.setDataSource(dataSource);
        context.setTablePrefix(PREFIX);
        RequestContext.set(context);
    }

    private static void acquireTransactionLock(Connection connection, String lockIdentity) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, lockIdentity);
            statement.execute();
        }
    }

    private boolean waitForAdvisoryLockWaiters(int expectedWaiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiters = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity " +
                            "WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                    Long.class
            );
            if (waiters != null && waiters >= expectedWaiters) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private long requestCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoted(PREFIX + "privacy_requests"),
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
