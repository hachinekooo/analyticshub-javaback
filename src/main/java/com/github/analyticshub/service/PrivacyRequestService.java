package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestCreatedResponse;
import com.github.analyticshub.dto.PrivacyRequestStatusResponse;
import com.github.analyticshub.dto.PrivacyRequestStatus;
import com.github.analyticshub.dto.PrivacyRequestSubmitRequest;
import com.github.analyticshub.dto.PrivacyRequestType;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class PrivacyRequestService {

    private static final System.Logger log = System.getLogger(PrivacyRequestService.class.getName());
    private static final long DUPLICATE_REQUEST_WINDOW_SECONDS = 600;

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;
    private final ProjectTransactionExecutor projectTransactions;

    public PrivacyRequestService(MultiDataSourceManager dataSourceManager,
                                 ObjectMapper objectMapper,
                                 EmailService emailService,
                                 ProjectTransactionExecutor projectTransactions) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.emailService = emailService;
        this.projectTransactions = projectTransactions;
    }

    public PrivacyRequestCreatedResponse submitExportRequest(PrivacyRequestSubmitRequest request) {
        return submitRequest(PrivacyRequestType.EXPORT, request);
    }

    public PrivacyRequestCreatedResponse submitDeleteRequest(PrivacyRequestSubmitRequest request) {
        return submitRequest(PrivacyRequestType.DELETE, request);
    }

    public PrivacyRequestStatusResponse getRequest(String requestId) {
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        RequestContext context = requireAuthenticatedContext();
        Device device = requireAuthenticatedDevice(context);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");

        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND user_id = ? AND device_id = ?::uuid " +
                                "AND request_id = ? LIMIT 1",
                        tableName
                ),
                ps -> {
                    ps.setString(1, context.getProjectId());
                    ps.setString(2, context.getUserId());
                    ps.setString(3, device.getDeviceId().toString());
                    ps.setString(4, normalizedRequestId);
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );

        if (row == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "未找到隐私请求", HttpStatus.NOT_FOUND);
        }

        return toStatusResponse(row);
    }

    public PrivacyRequestStatusResponse getLatestRequest() {
        RequestContext context = requireAuthenticatedContext();
        Device device = requireAuthenticatedDevice(context);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");

        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND user_id = ? AND device_id = ?::uuid " +
                                "ORDER BY requested_at DESC LIMIT 1",
                        tableName
                ),
                ps -> {
                    ps.setString(1, context.getProjectId());
                    ps.setString(2, context.getUserId());
                    ps.setString(3, device.getDeviceId().toString());
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );

        if (row == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "当前用户暂无隐私请求记录", HttpStatus.NOT_FOUND);
        }

        return toStatusResponse(row);
    }

    private PrivacyRequestCreatedResponse submitRequest(PrivacyRequestType type, PrivacyRequestSubmitRequest request) {
        RequestContext context = requireAuthenticatedContext();
        Device device = requireAuthenticatedDevice(context);

        PrivacyProcessor processor = PrivacyProcessor.from(request.processor());
        String contactEmail = normalizeRequired(request.contactEmail(), 255, "contactEmail");
        String source = normalizeSource(request.source());
        String requesterNote = normalizeOptional(request.requesterNote(), 4000);
        String metadataJson = toJson(request.metadata());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");
        String deviceId = device.getDeviceId().toString();

        SubmissionOutcome outcome = projectTransactions.execute(
                context.getDataSource(),
                jdbcTemplate -> submitRequestInTransaction(
                        jdbcTemplate,
                        tableName,
                        context.getProjectId(),
                        context.getUserId(),
                        deviceId,
                        type,
                        processor,
                        contactEmail,
                        source,
                        requesterNote,
                        metadataJson
                )
        );

        if (outcome.notification() != null) {
            SubmissionNotification notification = outcome.notification();
            emailService.sendPrivacyRequestSubmittedAlert(
                    notification.requestId(),
                    notification.projectId(),
                    notification.userId(),
                    notification.requestType(),
                    notification.processor(),
                    notification.contactEmail(),
                    notification.requestedAt()
            );
        }
        return outcome.response();
    }

    private SubmissionOutcome submitRequestInTransaction(JdbcTemplate jdbcTemplate,
                                                         String tableName,
                                                         String projectId,
                                                         String userId,
                                                         String deviceId,
                                                         PrivacyRequestType type,
                                                         PrivacyProcessor processor,
                                                         String contactEmail,
                                                         String source,
                                                         String requesterNote,
                                                         String metadataJson) {
        acquireDuplicateRequestLock(
                jdbcTemplate,
                duplicateLockIdentity(projectId, userId, deviceId, type, processor, contactEmail)
        );

        Instant now = Instant.now();
        StoredPrivacyRequest existing = findRecentSubmittedRequest(
                jdbcTemplate,
                tableName,
                projectId,
                userId,
                deviceId,
                type,
                processor,
                contactEmail,
                now
        );
        if (existing != null) {
            log.log(System.Logger.Level.INFO,
                    "Reuse recent privacy request: projectId={0}, requestId={1}, type={2}, processor={3}",
                    projectId, existing.requestId(), type.name(), processor.name());
            return new SubmissionOutcome(
                    toCreatedResponse(existing, "已有未处理的同类请求，后台将继续按原工单处理并通过邮件反馈结果"),
                    null
            );
        }

        String requestId = generateRequestId();
        Timestamp timestamp = Timestamp.from(now);
        String sql = String.format(
                "INSERT INTO %s (request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                        "contact_email, requester_note, metadata, requested_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                tableName
        );

        int affected = jdbcTemplate.update(sql,
                requestId,
                projectId,
                userId,
                deviceId,
                type.name(),
                processor.name(),
                source,
                PrivacyRequestStatus.SUBMITTED.name(),
                contactEmail,
                requesterNote,
                metadataJson,
                timestamp,
                timestamp,
                timestamp
        );

        if (affected != 1) {
            throw new BusinessException("PRIVACY_REQUEST_CREATE_FAILED", "隐私请求创建失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.log(System.Logger.Level.INFO,
                "Privacy request submitted: projectId={0}, requestId={1}, type={2}, processor={3}",
                projectId, requestId, type.name(), processor.name());

        PrivacyRequestCreatedResponse response = new PrivacyRequestCreatedResponse(
                requestId,
                type.name(),
                processor.name(),
                PrivacyRequestStatus.SUBMITTED.name(),
                now.toString(),
                contactEmail,
                "请求已创建，后台将人工处理并通过邮件反馈结果"
        );
        SubmissionNotification notification = new SubmissionNotification(
                requestId,
                projectId,
                userId,
                type.name(),
                processor.name(),
                contactEmail,
                now
        );
        return new SubmissionOutcome(response, notification);
    }

    private static void acquireDuplicateRequestLock(JdbcTemplate jdbcTemplate, String lockIdentity) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ps -> ps.setString(1, lockIdentity),
                rs -> {
                    rs.next();
                    return null;
                }
        );
    }

    static String duplicateLockIdentity(String projectId,
                                        String userId,
                                        String deviceId,
                                        PrivacyRequestType type,
                                        PrivacyProcessor processor,
                                        String contactEmail) {
        return "analyticshub:privacy-request-dedupe:v1:"
                + lengthPrefixed(projectId)
                + lengthPrefixed(userId)
                + lengthPrefixed(deviceId)
                + lengthPrefixed(type.name())
                + lengthPrefixed(processor.name())
                + lengthPrefixed(contactEmail);
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private StoredPrivacyRequest findRecentSubmittedRequest(JdbcTemplate jdbcTemplate,
                                                            String tableName,
                                                            String projectId,
                                                            String userId,
                                                            String deviceId,
                                                            PrivacyRequestType type,
                                                            PrivacyProcessor processor,
                                                            String contactEmail,
                                                            Instant now) {
        return jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND user_id = ? AND device_id = ?::uuid AND request_type = ? " +
                                "AND processor = ? AND contact_email = ? AND status = ? AND requested_at >= ? " +
                                "ORDER BY requested_at DESC LIMIT 1",
                        tableName
                ),
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, userId);
                    ps.setString(3, deviceId);
                    ps.setString(4, type.name());
                    ps.setString(5, processor.name());
                    ps.setString(6, contactEmail);
                    ps.setString(7, PrivacyRequestStatus.SUBMITTED.name());
                    ps.setTimestamp(8, Timestamp.from(now.minusSeconds(DUPLICATE_REQUEST_WINDOW_SECONDS)));
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );
    }

    private static RequestContext requireAuthenticatedContext() {
        RequestContext context = RequestContext.get();
        if (context == null || context.getProjectId() == null || context.getProjectId().isBlank() ||
                context.getDataSource() == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new BusinessException("UNAUTHORIZED", "请求未认证", HttpStatus.UNAUTHORIZED);
        }
        return context;
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
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private PrivacyRequestStatusResponse toStatusResponse(StoredPrivacyRequest row) {
        return new PrivacyRequestStatusResponse(
                row.requestId(),
                row.requestType(),
                row.processor(),
                row.status(),
                row.contactEmail(),
                toIso(row.requestedAt()),
                toIso(row.processedAt()),
                toIso(row.closedAt()),
                toIso(row.updatedAt())
        );
    }

    private static Device requireAuthenticatedDevice(RequestContext context) {
        Device device = context.getDevice();
        if (device == null || device.getDeviceId() == null) {
            throw new BusinessException(
                    "MISSING_DEVICE_ID",
                    "请求上下文缺少 deviceId",
                    HttpStatus.UNAUTHORIZED
            );
        }
        return device;
    }

    private PrivacyRequestCreatedResponse toCreatedResponse(StoredPrivacyRequest row, String message) {
        return new PrivacyRequestCreatedResponse(
                row.requestId(),
                row.requestType(),
                row.processor(),
                row.status(),
                toIso(row.requestedAt()),
                row.contactEmail(),
                message
        );
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata JSON 序列化失败");
        }
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "APP_SETTINGS";
        }
        String normalized = source.trim().toUpperCase();
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("source 长度不能超过 32");
        }
        if (!normalized.matches("^[A-Z0-9_-]+$")) {
            throw new IllegalArgumentException("source 仅支持 A-Z、0-9、_、-");
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

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("字段长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String generateRequestId() {
        return "prv_" + UUID.randomUUID().toString().replace("-", "");
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
            Instant updatedAt
    ) {
    }

    private record SubmissionOutcome(
            PrivacyRequestCreatedResponse response,
            SubmissionNotification notification
    ) {
    }

    private record SubmissionNotification(
            String requestId,
            String projectId,
            String userId,
            String requestType,
            String processor,
            String contactEmail,
            Instant requestedAt
    ) {
    }
}
