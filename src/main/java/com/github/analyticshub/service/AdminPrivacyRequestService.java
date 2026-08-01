package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminPrivacyNotifyRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestItem;
import com.github.analyticshub.dto.AdminPrivacyRequestUpdateRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestsResponse;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.dto.PrivacyRequestStatus;
import com.github.analyticshub.dto.PrivacyRequestType;
import com.github.analyticshub.dto.WorkOrderActivityItem;
import com.github.analyticshub.dto.WorkOrderNotificationQueuedResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminPrivacyRequestService {

    private static final System.Logger log = System.getLogger(AdminPrivacyRequestService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ProjectTransactionExecutor projectTransactions;

    public AdminPrivacyRequestService(MultiDataSourceManager dataSourceManager,
                                      ObjectMapper objectMapper,
                                      ProjectTransactionExecutor projectTransactions) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.projectTransactions = projectTransactions;
    }

    public AdminPrivacyRequestsResponse listRequests(String projectId,
                                                     String from,
                                                     String to,
                                                     Integer page,
                                                     Integer pageSize,
                                                     String status,
                                                     String requestType,
                                                     String processor,
                                                     String userId,
                                                     Boolean openOnly) {
        String normalizedProjectId = normalizeProjectId(projectId);
        boolean hasDateFilter = hasText(from) || hasText(to);
        AdminQueryUtils.Range range = hasDateFilter ? AdminQueryUtils.resolveRange(from, to) : null;
        AdminQueryUtils.Paging paging = AdminQueryUtils.resolvePaging(page, pageSize);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String tableName = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");

        PrivacyRequestStatus statusFilter = parseStatusNullable(status);
        PrivacyRequestType typeFilter = parseTypeNullable(requestType);
        PrivacyProcessor processorFilter = parseProcessorNullable(processor);

        StringBuilder where = new StringBuilder(" WHERE project_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(normalizedProjectId);
        if (range != null) {
            where.append(" AND requested_at >= ? AND requested_at < ? ");
            args.add(Timestamp.from(range.start()));
            args.add(Timestamp.from(range.end()));
        }

        if (statusFilter != null) {
            where.append(" AND status = ? ");
            args.add(statusFilter.name());
        } else if (openOnly == null ? !hasDateFilter : openOnly) {
            where.append(" AND status IN ('SUBMITTED', 'IN_PROGRESS') ");
        }
        if (typeFilter != null) {
            where.append(" AND request_type = ? ");
            args.add(typeFilter.name());
        }
        if (processorFilter != null) {
            where.append(" AND processor = ? ");
            args.add(processorFilter.name());
        }
        if (userId != null && !userId.isBlank()) {
            where.append(" AND user_id = ? ");
            args.add(userId.trim());
        }

        String countSql = String.format("SELECT COUNT(*) FROM %s %s", tableName, where);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());
        long totalValue = total == null ? 0L : total;

        String listSql = String.format(
                "SELECT request_id, user_id, device_id, request_type, processor, status, contact_email, requested_at, processed_at, closed_at, operator, version " +
                        "FROM %s %s ORDER BY requested_at DESC LIMIT ? OFFSET ?",
                tableName,
                where
        );

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(paging.pageSize());
        listArgs.add(paging.offset());

        List<AdminPrivacyRequestItem> items = jdbcTemplate.query(listSql,
                (rs, rowNum) -> new AdminPrivacyRequestItem(
                        rs.getString("request_id"),
                        rs.getString("user_id"),
                        rs.getString("device_id"),
                        rs.getString("request_type"),
                        rs.getString("processor"),
                        rs.getString("status"),
                        rs.getString("contact_email"),
                        toIso(rs.getTimestamp("requested_at")),
                        toIso(rs.getTimestamp("processed_at")),
                        toIso(rs.getTimestamp("closed_at")),
                        rs.getString("operator"),
                        rs.getLong("version")
                ),
                listArgs.toArray()
        );

        return new AdminPrivacyRequestsResponse(
                normalizedProjectId,
                range == null ? null : range.start().toString(),
                range == null ? null : range.end().toString(),
                paging.page(),
                paging.pageSize(),
                totalValue,
                items
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public PrivacyRequestDetailResponse getRequestDetail(String projectId, String requestId) {
        StoredPrivacyRequest row = requireRequest(normalizeProjectId(projectId), normalizeRequired(requestId, 64, "requestId"));
        return toDetailResponse(row);
    }

    public PrivacyRequestDetailResponse updateRequest(String projectId,
                                                      String requestId,
                                                      AdminPrivacyRequestUpdateRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        ProjectContext context = requireProject(normalizedProjectId);
        String requestTable = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");
        String activityTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_activities");
        String outboxTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_outbox");
        PrivacyRequestStatus targetStatus = PrivacyRequestStatus.from(request.status());

        PrivacyRequestDetailResponse result = projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            StoredPrivacyRequest existing = requireRequest(
                    jdbcTemplate,
                    requestTable,
                    normalizedProjectId,
                    normalizedRequestId,
                    true
            );
            requireExpectedVersion(existing, request.version());

            PrivacyRequestStatus currentStatus = PrivacyRequestStatus.from(existing.status());
            TransitionDecision transition = validateTransition(currentStatus, targetStatus);
            if (transition == TransitionDecision.IDEMPOTENT) {
                return toDetailResponse(existing);
            }

            String nextOperator = mergeText(existing.operator(), request.operator(), 64);
            String nextOperatorNote = mergeText(existing.operatorNote(), request.operatorNote(), 4000);
            String resultPayloadJson = existing.resultPayloadText();
            if (request.resultPayload() != null) {
                resultPayloadJson = toJson(request.resultPayload(), "resultPayload");
            }

            Instant now = Instant.now();
            Instant nextProcessedAt = existing.processedAt() == null ? now : existing.processedAt();
            Instant nextClosedAt = targetStatus.isFinalStatus() ? now : null;

            int affected = jdbcTemplate.update(
                    String.format(
                            "UPDATE %s SET status = ?, operator = ?, operator_note = ?, result_payload = ?::jsonb, " +
                                    "processed_at = ?, closed_at = ?, updated_at = ?, version = version + 1 " +
                                    "WHERE project_id = ? AND request_id = ? AND version = ?",
                            requestTable
                    ),
                    targetStatus.name(),
                    nextOperator,
                    nextOperatorNote,
                    resultPayloadJson,
                    toTimestamp(nextProcessedAt),
                    toTimestamp(nextClosedAt),
                    Timestamp.from(now),
                    normalizedProjectId,
                    normalizedRequestId,
                    existing.version()
            );
            if (affected != 1) {
                throw versionConflict(existing.version());
            }

            Map<String, Object> transitionDetails = new LinkedHashMap<>();
            transitionDetails.put("versionBefore", existing.version());
            transitionDetails.put("versionAfter", existing.version() + 1);
            transitionDetails.put("operatorNoteChanged", request.operatorNote() != null && !request.operatorNote().isBlank());
            transitionDetails.put("resultPayloadChanged", request.resultPayload() != null);
            appendActivity(
                    jdbcTemplate,
                    activityTable,
                    normalizedProjectId,
                    normalizedRequestId,
                    "STATUS_CHANGED",
                    currentStatus.name(),
                    targetStatus.name(),
                    nextOperator,
                    transitionDetails
            );

            if (Boolean.TRUE.equals(request.notifyUser())) {
                String contactEmail = normalizeRequired(existing.contactEmail(), 255, "contactEmail");
                String message = request.notificationMessage();
                if (message == null || message.isBlank()) {
                    message = defaultNotificationMessage(normalizedRequestId, targetStatus);
                }
                enqueueNotification(
                        jdbcTemplate,
                        outboxTable,
                        activityTable,
                        normalizedProjectId,
                        normalizedRequestId,
                        contactEmail,
                        "[Analytics Hub] Privacy Request " + normalizedRequestId + " " + targetStatus.name(),
                        message.trim(),
                        nextOperator
                );
            }

            return toDetailResponse(requireRequest(
                    jdbcTemplate,
                    requestTable,
                    normalizedProjectId,
                    normalizedRequestId,
                    false
            ));
        });

        log.log(System.Logger.Level.INFO,
                "Privacy work order updated: projectId={0}, requestId={1}, status={2}",
                normalizedProjectId, normalizedRequestId, targetStatus.name());
        return result;
    }

    public WorkOrderNotificationQueuedResponse notifyUser(String projectId,
                                                          String requestId,
                                                          AdminPrivacyNotifyRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        ProjectContext context = requireProject(normalizedProjectId);
        String requestTable = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");
        String activityTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_activities");
        String outboxTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_outbox");
        String subject = normalizeRequired(request.subject(), 120, "subject");
        String message = normalizeRequired(request.message(), 4000, "message");

        String notificationId = projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            StoredPrivacyRequest existing = requireRequest(
                    jdbcTemplate,
                    requestTable,
                    normalizedProjectId,
                    normalizedRequestId,
                    true
            );
            String contactEmail = normalizeRequired(existing.contactEmail(), 255, "contactEmail");
            String operator = mergeText(existing.operator(), request.operator(), 64);
            return enqueueNotification(
                    jdbcTemplate,
                    outboxTable,
                    activityTable,
                    normalizedProjectId,
                    normalizedRequestId,
                    contactEmail,
                    subject,
                    message,
                    operator
            );
        });

        return new WorkOrderNotificationQueuedResponse(normalizedRequestId, notificationId, "QUEUED");
    }

    public List<WorkOrderActivityItem> listActivities(String projectId, String requestId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String requestTable = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");
        String activityTable = dataSourceManager.getTableName(normalizedProjectId, "work_order_activities");
        requireRequest(jdbcTemplate, requestTable, normalizedProjectId, normalizedRequestId, false);
        return jdbcTemplate.query(
                String.format(
                        "SELECT activity_id, activity_type, from_status, to_status, actor, details::text AS details_text, created_at " +
                                "FROM %s WHERE project_id = ? AND work_order_type = 'PRIVACY_REQUEST' AND work_order_id = ? " +
                                "ORDER BY created_at ASC, id ASC",
                        activityTable
                ),
                (rs, rowNum) -> new WorkOrderActivityItem(
                        rs.getString("activity_id"),
                        rs.getString("activity_type"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("actor"),
                        parseJson(rs.getString("details_text")),
                        toIso(rs.getTimestamp("created_at"))
                ),
                normalizedProjectId,
                normalizedRequestId
        );
    }

    private StoredPrivacyRequest requireRequest(String projectId, String requestId) {
        ProjectContext context = requireProject(projectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String tableName = dataSourceManager.getTableName(projectId, "privacy_requests");
        return requireRequest(jdbcTemplate, tableName, projectId, requestId, false);
    }

    private StoredPrivacyRequest requireRequest(JdbcTemplate jdbcTemplate,
                                                String tableName,
                                                String projectId,
                                                String requestId,
                                                boolean forUpdate) {
        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at, version " +
                                "FROM %s WHERE project_id = ? AND request_id = ? LIMIT 1%s",
                        tableName,
                        forUpdate ? " FOR UPDATE" : ""
                ),
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, requestId);
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );

        if (row == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "未找到隐私请求", HttpStatus.NOT_FOUND);
        }

        return row;
    }

    private StoredPrivacyRequest mapStoredRow(ResultSet rs) throws java.sql.SQLException {
        Timestamp requestedAt = rs.getTimestamp("requested_at");
        Timestamp processedAt = rs.getTimestamp("processed_at");
        Timestamp closedAt = rs.getTimestamp("closed_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");

        return new StoredPrivacyRequest(
                rs.getString("request_id"),
                rs.getString("project_id"),
                rs.getString("user_id"),
                rs.getString("device_id"),
                rs.getString("request_type"),
                rs.getString("processor"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("contact_email"),
                rs.getString("requester_note"),
                rs.getString("operator"),
                rs.getString("operator_note"),
                rs.getString("result_payload_text"),
                rs.getString("metadata_text"),
                requestedAt == null ? null : requestedAt.toInstant(),
                processedAt == null ? null : processedAt.toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                rs.getLong("version")
        );
    }

    private PrivacyRequestDetailResponse toDetailResponse(StoredPrivacyRequest row) {
        return new PrivacyRequestDetailResponse(
                row.requestId(),
                row.projectId(),
                row.userId(),
                row.deviceId(),
                row.requestType(),
                row.processor(),
                row.source(),
                row.status(),
                row.contactEmail(),
                row.requesterNote(),
                row.operator(),
                row.operatorNote(),
                parseJson(row.resultPayloadText()),
                parseJson(row.metadataText()),
                toIso(row.requestedAt()),
                toIso(row.processedAt()),
                toIso(row.closedAt()),
                toIso(row.updatedAt()),
                row.version()
        );
    }

    private void requireExpectedVersion(StoredPrivacyRequest existing, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != existing.version()) {
            throw versionConflict(existing.version());
        }
    }

    private static BusinessException versionConflict(long currentVersion) {
        return new BusinessException(
                "PRIVACY_REQUEST_VERSION_CONFLICT",
                "工单版本冲突，请刷新后重试（当前版本 " + currentVersion + "）",
                HttpStatus.CONFLICT
        );
    }

    private static TransitionDecision validateTransition(PrivacyRequestStatus current,
                                                         PrivacyRequestStatus target) {
        if (current.isFinalStatus()) {
            if (current == target) {
                return TransitionDecision.IDEMPOTENT;
            }
            throw invalidTransition(current, target);
        }
        boolean allowed = switch (current) {
            case SUBMITTED -> target == PrivacyRequestStatus.IN_PROGRESS || target.isFinalStatus();
            case IN_PROGRESS -> target.isFinalStatus();
            default -> false;
        };
        if (!allowed) {
            throw invalidTransition(current, target);
        }
        return TransitionDecision.APPLY;
    }

    private static BusinessException invalidTransition(PrivacyRequestStatus current,
                                                       PrivacyRequestStatus target) {
        return new BusinessException(
                "PRIVACY_REQUEST_INVALID_TRANSITION",
                "不允许将工单状态从 " + current.name() + " 变更为 " + target.name(),
                HttpStatus.CONFLICT
        );
    }

    private String enqueueNotification(JdbcTemplate jdbcTemplate,
                                       String outboxTable,
                                       String activityTable,
                                       String projectId,
                                       String requestId,
                                       String recipient,
                                       String subject,
                                       String content,
                                       String actor) {
        String notificationId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (notification_id, project_id, work_order_type, work_order_id, channel, " +
                                "recipient, subject, content, status, next_attempt_at, created_at, updated_at) " +
                                "VALUES (?, ?, 'PRIVACY_REQUEST', ?, 'EMAIL', ?, ?, ?, 'PENDING', NOW(), NOW(), NOW())",
                        outboxTable
                ),
                notificationId,
                projectId,
                requestId,
                recipient,
                subject,
                content
        );

        appendActivity(
                jdbcTemplate,
                activityTable,
                projectId,
                requestId,
                "NOTIFICATION_QUEUED",
                null,
                null,
                actor,
                Map.of("notificationId", notificationId, "channel", "EMAIL")
        );
        return notificationId;
    }

    private void appendActivity(JdbcTemplate jdbcTemplate,
                                String activityTable,
                                String projectId,
                                String requestId,
                                String activityType,
                                String fromStatus,
                                String toStatus,
                                String actor,
                                Map<String, Object> details) {
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (activity_id, project_id, work_order_type, work_order_id, activity_type, " +
                                "from_status, to_status, actor, details, created_at) " +
                                "VALUES (?, ?, 'PRIVACY_REQUEST', ?, ?, ?, ?, ?, ?::jsonb, ?)",
                        activityTable
                ),
                UUID.randomUUID().toString(),
                projectId,
                requestId,
                activityType,
                fromStatus,
                toStatus,
                normalizeActor(actor),
                toJson(details, "activityDetails"),
                Timestamp.from(Instant.now())
        );
    }

    private static String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? "admin" : actor.trim();
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to parse JSON field", e);
            return null;
        }
    }

    private String toJson(Map<String, Object> payload, String fieldName) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " JSON 序列化失败");
        }
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (normalizedProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidProject(normalizedProjectId);
        } catch (Exception e) {
            throw BusinessException.invalidProject(normalizedProjectId);
        }

        if (projectConfig == null) {
            throw BusinessException.invalidProject(normalizedProjectId);
        }
        if (!Boolean.TRUE.equals(projectConfig.isActive())) {
            throw BusinessException.projectInactive();
        }

        try {
            DataSource dataSource = dataSourceManager.getDataSource(normalizedProjectId);
            return new ProjectContext(projectConfig, dataSource);
        } catch (Exception e) {
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }
    }

    private static PrivacyRequestStatus parseStatusNullable(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return PrivacyRequestStatus.from(status);
    }

    private static PrivacyRequestType parseTypeNullable(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            return null;
        }
        return PrivacyRequestType.from(requestType);
    }

    private static PrivacyProcessor parseProcessorNullable(String processor) {
        if (processor == null || processor.isBlank()) {
            return null;
        }
        return PrivacyProcessor.from(processor);
    }

    private static String mergeText(String original, String override, int maxLength) {
        if (override == null || override.isBlank()) {
            return original;
        }
        String normalized = override.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("字段长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeRequired(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeProjectId(String projectId) {
        if (projectId == null) {
            return "";
        }
        String stripped = projectId.strip();
        StringBuilder builder = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private static String defaultNotificationMessage(String requestId, PrivacyRequestStatus status) {
        return "您的数据请求（" + requestId + "）状态已更新为 " + status.name() + "。如需协助请回复此邮件。";
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {
    }

    private record StoredPrivacyRequest(
            String requestId,
            String projectId,
            String userId,
            String deviceId,
            String requestType,
            String processor,
            String source,
            String status,
            String contactEmail,
            String requesterNote,
            String operator,
            String operatorNote,
            String resultPayloadText,
            String metadataText,
            Instant requestedAt,
            Instant processedAt,
            Instant closedAt,
            Instant updatedAt,
            long version
    ) {
    }

    private enum TransitionDecision {
        APPLY,
        IDEMPOTENT
    }
}
