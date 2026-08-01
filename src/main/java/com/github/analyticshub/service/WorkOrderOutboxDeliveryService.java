package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.WorkOrderOutboxDeliveryResult;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers transactional work-order notifications.
 *
 * <p>Claims are committed before SMTP I/O and use {@code FOR UPDATE SKIP LOCKED},
 * so concurrent application instances cannot normally deliver the same row.
 * A timed-out PROCESSING claim can be reclaimed after a crashed worker. This is
 * therefore an at-least-once delivery pipeline (至少投递一次), which is the
 * strongest guarantee possible without provider-side idempotency.</p>
 */
@Service
public class WorkOrderOutboxDeliveryService {

    private static final System.Logger log = System.getLogger(WorkOrderOutboxDeliveryService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ProjectTransactionExecutor projectTransactions;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;
    private final long claimTimeoutSeconds;
    private final long retryDelaySeconds;

    public WorkOrderOutboxDeliveryService(
            MultiDataSourceManager dataSourceManager,
            ProjectTransactionExecutor projectTransactions,
            EmailService emailService,
            ObjectMapper objectMapper,
            @Value("${app.work-order.outbox.claim-timeout-seconds:300}") long claimTimeoutSeconds,
            @Value("${app.work-order.outbox.retry-delay-seconds:60}") long retryDelaySeconds
    ) {
        this.dataSourceManager = dataSourceManager;
        this.projectTransactions = projectTransactions;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.claimTimeoutSeconds = Math.max(30, claimTimeoutSeconds);
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
    }

    public WorkOrderOutboxDeliveryResult deliverPending(String projectId, int requestedBatchSize) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (requestedBatchSize < 1 || requestedBatchSize > 100) {
            throw new IllegalArgumentException("batchSize 必须在 1 到 100 之间");
        }
        if (!emailService.isDeliveryEnabled()) {
            throw new BusinessException(
                    "MAIL_DELIVERY_DISABLED",
                    "邮件投递未配置，通知仍保留在待发送队列中",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        int batchSize = requestedBatchSize;
        DataSource dataSource = requireProjectDataSource(normalizedProjectId);
        String outboxTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_outbox");
        String activityTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_activities");
        String workerId = UUID.randomUUID().toString();

        List<ClaimedNotification> claimed = claim(
                dataSource,
                outboxTable,
                normalizedProjectId,
                workerId,
                batchSize
        );

        int sent = 0;
        int retryScheduled = 0;
        int dead = 0;
        for (ClaimedNotification notification : claimed) {
            EmailDeliveryStatus deliveryStatus;
            try {
                deliveryStatus = emailService.sendPrivacyUserNotification(
                        notification.recipient(),
                        notification.subject(),
                        notification.content()
                );
            } catch (RuntimeException exception) {
                // Do not log the exception message: SMTP errors can contain PII.
                log.log(System.Logger.Level.ERROR,
                        "Work-order email delivery raised an exception: projectId={0}, failureType={1}",
                        normalizedProjectId,
                        exception.getClass().getSimpleName());
                deliveryStatus = EmailDeliveryStatus.FAILED;
            }
            if (deliveryStatus == null) {
                deliveryStatus = EmailDeliveryStatus.FAILED;
            }
            OutboxOutcome outcome = finalizeAttempt(
                    dataSource,
                    outboxTable,
                    activityTable,
                    notification,
                    workerId,
                    deliveryStatus
            );
            switch (outcome) {
                case SENT -> sent++;
                case RETRY -> retryScheduled++;
                case DEAD -> dead++;
                case LOST_CLAIM -> {
                    // Another worker reclaimed the timed-out claim. Do not overwrite it.
                }
            }
        }

        if (!claimed.isEmpty()) {
            log.log(System.Logger.Level.INFO,
                    "Work-order outbox batch delivered: projectId={0}, claimed={1}, sent={2}, retry={3}, dead={4}",
                    normalizedProjectId, claimed.size(), sent, retryScheduled, dead);
        }
        return new WorkOrderOutboxDeliveryResult(
                normalizedProjectId,
                claimed.size(),
                sent,
                retryScheduled,
                dead
        );
    }

    private List<ClaimedNotification> claim(DataSource dataSource,
                                            String outboxTable,
                                            String projectId,
                                            String workerId,
                                            int batchSize) {
        String sql = String.format(
                "WITH candidates AS (" +
                        " SELECT id FROM %s" +
                        " WHERE project_id = ? AND attempt_count < max_attempts AND (" +
                        "   (status IN ('PENDING', 'RETRY') AND next_attempt_at <= NOW())" +
                        "   OR (status = 'PROCESSING' AND claimed_at < NOW() - make_interval(secs => ?))" +
                        " ) ORDER BY created_at ASC, id ASC FOR UPDATE SKIP LOCKED LIMIT ?" +
                        ") UPDATE %s AS outbox" +
                        " SET status = 'PROCESSING', claimed_by = ?, claimed_at = NOW()," +
                        "     attempt_count = outbox.attempt_count + 1, updated_at = NOW()" +
                        " FROM candidates WHERE outbox.id = candidates.id" +
                        " RETURNING outbox.id, outbox.notification_id, outbox.project_id," +
                        " outbox.work_order_type, outbox.work_order_id, outbox.recipient," +
                        " outbox.subject, outbox.content, outbox.attempt_count, outbox.max_attempts",
                outboxTable,
                outboxTable
        );
        return projectTransactions.execute(dataSource, jdbcTemplate -> jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, projectId);
                    ps.setDouble(2, claimTimeoutSeconds);
                    ps.setInt(3, batchSize);
                    ps.setString(4, workerId);
                },
                (rs, rowNum) -> mapClaim(rs)
        ));
    }

    private OutboxOutcome finalizeAttempt(DataSource dataSource,
                                          String outboxTable,
                                          String activityTable,
                                          ClaimedNotification notification,
                                          String workerId,
                                          EmailDeliveryStatus deliveryStatus) {
        return projectTransactions.execute(dataSource, jdbcTemplate -> {
            Integer currentAttempt = jdbcTemplate.query(
                    String.format(
                            "SELECT attempt_count FROM %s WHERE id = ? AND status = 'PROCESSING' AND claimed_by = ? FOR UPDATE",
                            outboxTable
                    ),
                    ps -> {
                        ps.setLong(1, notification.id());
                        ps.setString(2, workerId);
                    },
                    rs -> rs.next() ? rs.getInt("attempt_count") : null
            );
            if (currentAttempt == null) {
                return OutboxOutcome.LOST_CLAIM;
            }

            boolean sent = deliveryStatus == EmailDeliveryStatus.SENT;
            boolean terminalFailure = deliveryStatus == EmailDeliveryStatus.INVALID_RECIPIENT
                    || currentAttempt >= notification.maxAttempts();
            OutboxOutcome outcome = sent
                    ? OutboxOutcome.SENT
                    : terminalFailure ? OutboxOutcome.DEAD : OutboxOutcome.RETRY;
            String outboxStatus = switch (outcome) {
                case SENT -> "SENT";
                case RETRY -> "RETRY";
                case DEAD -> "DEAD";
                case LOST_CLAIM -> throw new IllegalStateException("unreachable");
            };
            int affected = jdbcTemplate.update(
                    String.format(
                            "UPDATE %s SET status = ?," +
                                    " next_attempt_at = CASE WHEN ? THEN NOW() + make_interval(secs => ?) ELSE NOW() END," +
                                    " claimed_at = NULL, claimed_by = NULL," +
                                    " last_delivery_status = ?, last_error = ?," +
                                    " sent_at = CASE WHEN ? THEN NOW() ELSE NULL END, updated_at = NOW()" +
                                    " WHERE id = ? AND status = 'PROCESSING' AND claimed_by = ?",
                            outboxTable
                    ),
                    outboxStatus,
                    outcome == OutboxOutcome.RETRY,
                    retryDelaySeconds,
                    deliveryStatus.name(),
                    sent ? null : deliveryStatus.name(),
                    sent,
                    notification.id(),
                    workerId
            );
            if (affected != 1) {
                return OutboxOutcome.LOST_CLAIM;
            }

            appendDeliveryActivity(
                    jdbcTemplate,
                    activityTable,
                    notification,
                    sent ? "NOTIFICATION_SENT" : "NOTIFICATION_DELIVERY_FAILED",
                    deliveryStatus,
                    outcome,
                    currentAttempt
            );
            return outcome;
        });
    }

    private void appendDeliveryActivity(JdbcTemplate jdbcTemplate,
                                        String activityTable,
                                        ClaimedNotification notification,
                                        String activityType,
                                        EmailDeliveryStatus deliveryStatus,
                                        OutboxOutcome outcome,
                                        int attempt) {
        String details;
        try {
            details = objectMapper.writeValueAsString(Map.of(
                    "notificationId", notification.notificationId(),
                    "channel", "EMAIL",
                    "deliveryStatus", deliveryStatus.name(),
                    "outboxStatus", outcome.name(),
                    "attempt", attempt
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize outbox activity", exception);
        }
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (activity_id, project_id, work_order_type, work_order_id, activity_type," +
                                " actor, details, created_at) VALUES (?, ?, ?, ?, ?, 'outbox-worker', ?::jsonb, NOW())",
                        activityTable
                ),
                UUID.randomUUID().toString(),
                notification.projectId(),
                notification.workOrderType(),
                notification.workOrderId(),
                activityType,
                details
        );
    }

    private DataSource requireProjectDataSource(String projectId) {
        MultiDataSourceManager.ProjectConfig config;
        try {
            config = dataSourceManager.getProjectConfig(projectId);
        } catch (Exception exception) {
            throw BusinessException.invalidProject(projectId);
        }
        if (config == null) {
            throw BusinessException.invalidProject(projectId);
        }
        if (!Boolean.TRUE.equals(config.isActive())) {
            throw BusinessException.projectInactive();
        }
        try {
            return dataSourceManager.getDataSource(projectId);
        } catch (Exception exception) {
            throw new BusinessException(
                    "PROJECT_DB_UNAVAILABLE",
                    "项目数据库不可用",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private static ClaimedNotification mapClaim(ResultSet rs) throws java.sql.SQLException {
        return new ClaimedNotification(
                rs.getLong("id"),
                rs.getString("notification_id"),
                rs.getString("project_id"),
                rs.getString("work_order_type"),
                rs.getString("work_order_id"),
                rs.getString("recipient"),
                rs.getString("subject"),
                rs.getString("content"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts")
        );
    }

    private static String normalizeProjectId(String projectId) {
        if (projectId == null) {
            return "";
        }
        String stripped = projectId.strip();
        StringBuilder builder = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isWhitespace(c)
                    && !Character.isSpaceChar(c)
                    && Character.getType(c) != Character.FORMAT) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private record ClaimedNotification(
            long id,
            String notificationId,
            String projectId,
            String workOrderType,
            String workOrderId,
            String recipient,
            String subject,
            String content,
            int attemptCount,
            int maxAttempts
    ) {
    }

    private enum OutboxOutcome {
        SENT,
        RETRY,
        DEAD,
        LOST_CLAIM
    }
}
