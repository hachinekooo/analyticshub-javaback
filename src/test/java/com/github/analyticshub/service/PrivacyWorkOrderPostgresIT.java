package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.AdminPrivacyNotifyRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestsResponse;
import com.github.analyticshub.dto.AdminPrivacyRequestUpdateRequest;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.dto.WorkOrderNotificationQueuedResponse;
import com.github.analyticshub.dto.WorkOrderOutboxDeliveryResult;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class PrivacyWorkOrderPostgresIT {

    private static final String PROJECT_ID = "privacy_work_order";
    private static final String PREFIX = "work_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("privacy_work_order_test")
            .withUsername("work_order_test")
            .withPassword("work_order_test_password");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private MultiDataSourceManager dataSourceManager;
    private EmailService emailService;
    private AdminPrivacyRequestService adminService;
    private WorkOrderOutboxDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        String schema = "privacy_work_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        dataSourceManager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig config = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Privacy Work Order Test",
                "localhost",
                5432,
                "privacy_work_order_test",
                schema,
                "work_order_test",
                "work_order_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(config);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "privacy_requests"))
                .thenReturn(quoted(PREFIX + "privacy_requests"));
        when(dataSourceManager.getTableName(PROJECT_ID, "work_order_activities"))
                .thenReturn(quoted(PREFIX + "work_order_activities"));
        when(dataSourceManager.getTableName(PROJECT_ID, "work_order_outbox"))
                .thenReturn(quoted(PREFIX + "work_order_outbox"));

        ObjectMapper objectMapper = JsonMapper.builder().build();
        ProjectTransactionExecutor transactions = new ProjectTransactionExecutor();
        emailService = mock(EmailService.class);
        when(emailService.isDeliveryEnabled()).thenReturn(true);
        adminService = new AdminPrivacyRequestService(dataSourceManager, objectMapper, transactions);
        deliveryService = new WorkOrderOutboxDeliveryService(
                dataSourceManager,
                transactions,
                emailService,
                objectMapper,
                300,
                1
        );
    }

    @Test
    void enforcesForwardOnlyStateMachineAndImmutableActivityHistory() {
        String requestId = insertSubmittedRequest();

        PrivacyRequestDetailResponse inProgress = adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(0, "IN_PROGRESS", false)
        );
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.version()).isEqualTo(1);

        assertThatThrownBy(() -> adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(1, "SUBMITTED", false)
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("PRIVACY_REQUEST_INVALID_TRANSITION");
            assertThat(exception.getHttpStatus().value()).isEqualTo(409);
        });

        PrivacyRequestDetailResponse completed = adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(1, "COMPLETED", false)
        );
        assertThat(completed.version()).isEqualTo(2);

        PrivacyRequestDetailResponse idempotent = adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(2, "COMPLETED", false)
        );
        assertThat(idempotent.version()).isEqualTo(2);
        assertThat(activityCount(requestId)).isEqualTo(3);

        assertThatThrownBy(() -> adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(2, "REJECTED", false)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("PRIVACY_REQUEST_INVALID_TRANSITION"));

        Long firstActivityId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + quoted(PREFIX + "work_order_activities") + " ORDER BY id LIMIT 1",
                Long.class
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "work_order_activities") + " SET actor = 'tampered' WHERE id = ?",
                firstActivityId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentUpdatesWithSameVersionAllowExactlyOneWinner() throws Exception {
        String requestId = insertSubmittedRequest();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(() -> updateAfter(ready, start, requestId, "COMPLETED"));
            Future<Object> second = executor.submit(() -> updateAfter(ready, start, requestId, "REJECTED"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PrivacyRequestDetailResponse.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .map(BusinessException::getCode))
                    .containsExactly("PRIVACY_REQUEST_VERSION_CONFLICT");
            assertThat(activityCount(requestId)).isEqualTo(2);
            assertThat(adminService.getRequestDetail(PROJECT_ID, requestId).version()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void notifyAndUpdateNotificationAreQueuedAtomicallyInsteadOfClaimingSent() {
        String requestId = insertSubmittedRequest();

        WorkOrderNotificationQueuedResponse queued = adminService.notifyUser(
                PROJECT_ID,
                requestId,
                new AdminPrivacyNotifyRequest("Your request", "We are processing it", "operator-a")
        );
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.notificationId()).isNotBlank();
        assertThat(outboxStatus(queued.notificationId())).isEqualTo("PENDING");
        assertThat(activityTypes(requestId)).containsExactly("WORK_ORDER_CREATED", "NOTIFICATION_QUEUED");

        PrivacyRequestDetailResponse updated = adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(0, "IN_PROGRESS", true)
        );
        assertThat(updated.version()).isEqualTo(1);
        assertThat(count(PREFIX + "work_order_outbox")).isEqualTo(2);
        assertThat(activityTypes(requestId)).containsExactly(
                "WORK_ORDER_CREATED",
                "NOTIFICATION_QUEUED",
                "STATUS_CHANGED",
                "NOTIFICATION_QUEUED"
        );
        verify(emailService, times(0)).sendPrivacyUserNotification(anyString(), anyString(), anyString());
    }

    @Test
    void statusUpdateRollsBackWhenNotificationCannotBeQueued() {
        String requestId = insertSubmittedRequest();
        jdbcTemplate.execute("DROP TABLE " + quoted(PREFIX + "work_order_outbox"));

        assertThatThrownBy(() -> adminService.updateRequest(
                PROJECT_ID,
                requestId,
                update(0, "IN_PROGRESS", true)
        )).isInstanceOf(DataAccessException.class);

        PrivacyRequestDetailResponse unchanged = adminService.getRequestDetail(PROJECT_ID, requestId);
        assertThat(unchanged.status()).isEqualTo("SUBMITTED");
        assertThat(unchanged.version()).isZero();
        assertThat(activityTypes(requestId)).containsExactly("WORK_ORDER_CREATED");
    }

    @Test
    void deliveryRecordsFailureAndSentWithRetry() {
        String requestId = insertSubmittedRequest();
        String notificationId = adminService.notifyUser(
                PROJECT_ID,
                requestId,
                new AdminPrivacyNotifyRequest("Result", "Your export is ready", "operator-a")
        ).notificationId();

        when(emailService.sendPrivacyUserNotification(anyString(), anyString(), anyString()))
                .thenReturn(EmailDeliveryStatus.FAILED)
                .thenReturn(EmailDeliveryStatus.SENT);

        WorkOrderOutboxDeliveryResult failed = deliveryService.deliverPending(PROJECT_ID, 10);
        assertThat(failed.retryScheduled()).isEqualTo(1);
        assertThat(outboxStatus(notificationId)).isEqualTo("RETRY");

        makeRetryDue(notificationId);
        WorkOrderOutboxDeliveryResult sent = deliveryService.deliverPending(PROJECT_ID, 10);
        assertThat(sent.sent()).isEqualTo(1);
        assertThat(outboxStatus(notificationId)).isEqualTo("SENT");
        assertThat(activityTypes(requestId)).containsExactly(
                "WORK_ORDER_CREATED",
                "NOTIFICATION_QUEUED",
                "NOTIFICATION_DELIVERY_FAILED",
                "NOTIFICATION_SENT"
        );
    }

    @Test
    void disabledMailDoesNotClaimOrConsumeAttempts() {
        String requestId = insertSubmittedRequest();
        adminService.notifyUser(
                PROJECT_ID,
                requestId,
                new AdminPrivacyNotifyRequest("Result", "Your export is ready", "operator-a")
        );
        when(emailService.isDeliveryEnabled()).thenReturn(false);

        assertThatThrownBy(() -> deliveryService.deliverPending(PROJECT_ID, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MAIL_DELIVERY_DISABLED"));
        assertThat(attemptCount()).isZero();
        assertThat(activityTypes(requestId)).containsExactly(
                "WORK_ORDER_CREATED",
                "NOTIFICATION_QUEUED"
        );
    }

    @Test
    void concurrentWorkersUseSkipLockedAndClaimOnce() throws Exception {
        String requestId = insertSubmittedRequest();
        adminService.notifyUser(
                PROJECT_ID,
                requestId,
                new AdminPrivacyNotifyRequest("Result", "Your request is complete", "operator-a")
        );

        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(emailService.sendPrivacyUserNotification(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    sending.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return EmailDeliveryStatus.SENT;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkOrderOutboxDeliveryResult> first = executor.submit(
                    () -> deliveryService.deliverPending(PROJECT_ID, 10));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();

            WorkOrderOutboxDeliveryResult second = deliveryService.deliverPending(PROJECT_ID, 10);
            assertThat(second.claimed()).isZero();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).sent()).isEqualTo(1);
            verify(emailService, times(1)).sendPrivacyUserNotification(anyString(), anyString(), anyString());
            assertThat(attemptCount()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void defaultListIsAnUnboundedOpenQueueAndHistoryCanBeRequestedExplicitly() {
        String oldOpenRequest = insertSubmittedRequest();
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "privacy_requests") +
                        " SET requested_at = NOW() - INTERVAL '90 days' WHERE request_id = ?",
                oldOpenRequest
        );
        String completedRequest = insertSubmittedRequest();
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "privacy_requests") +
                        " SET status = 'COMPLETED', requested_at = NOW() - INTERVAL '90 days' WHERE request_id = ?",
                completedRequest
        );

        AdminPrivacyRequestsResponse openQueue = adminService.listRequests(
                PROJECT_ID, null, null, 1, 20, null, null, null, null, null
        );
        assertThat(openQueue.rangeStart()).isNull();
        assertThat(openQueue.rangeEnd()).isNull();
        assertThat(openQueue.items()).extracting(item -> item.requestId())
                .containsExactly(oldOpenRequest);

        AdminPrivacyRequestsResponse history = adminService.listRequests(
                PROJECT_ID, null, null, 1, 20, null, null, null, null, false
        );
        assertThat(history.items()).extracting(item -> item.requestId())
                .containsExactlyInAnyOrder(oldOpenRequest, completedRequest);
    }

    private Object updateAfter(CountDownLatch ready,
                               CountDownLatch start,
                               String requestId,
                               String status) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return adminService.updateRequest(PROJECT_ID, requestId, update(0, status, false));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private String insertSubmittedRequest() {
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO " + quoted(PREFIX + "privacy_requests") +
                        " (request_id, project_id, user_id, device_id, request_type, processor, source, status, contact_email)" +
                        " VALUES (?, ?, ?, ?::uuid, 'EXPORT', 'ANALYTICSHUB', 'APP_SETTINGS', 'SUBMITTED', ?)",
                requestId,
                PROJECT_ID,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "user@example.com"
        );
        return requestId;
    }

    private static AdminPrivacyRequestUpdateRequest update(long version,
                                                            String status,
                                                            boolean notifyUser) {
        return new AdminPrivacyRequestUpdateRequest(
                version,
                status,
                "operator-a",
                "processed by test operator",
                Map.of("result", "ok"),
                notifyUser,
                notifyUser ? "Your request status changed" : null
        );
    }

    private long activityCount(String requestId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoted(PREFIX + "work_order_activities") + " WHERE work_order_id = ?",
                Long.class,
                requestId
        );
        return value == null ? 0 : value;
    }

    private List<String> activityTypes(String requestId) {
        return jdbcTemplate.queryForList(
                "SELECT activity_type FROM " + quoted(PREFIX + "work_order_activities") +
                        " WHERE work_order_id = ? ORDER BY created_at ASC, id ASC",
                String.class,
                requestId
        );
    }

    private String outboxStatus(String notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + quoted(PREFIX + "work_order_outbox") + " WHERE notification_id = ?",
                String.class,
                notificationId
        );
    }

    private int attemptCount() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM " + quoted(PREFIX + "work_order_outbox") + " LIMIT 1",
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private void makeRetryDue(String notificationId) {
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "work_order_outbox") +
                        " SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE notification_id = ?",
                notificationId
        );
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + quoted(table), Long.class);
        return value == null ? 0 : value;
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
