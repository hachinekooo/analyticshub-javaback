package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestCreatedResponse;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.dto.PrivacyRequestStatus;
import com.github.analyticshub.dto.PrivacyRequestSubmitRequest;
import com.github.analyticshub.dto.PrivacyRequestType;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
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

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;

    public PrivacyRequestService(MultiDataSourceManager dataSourceManager,
                                 ObjectMapper objectMapper,
                                 EmailService emailService) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.emailService = emailService;
    }

    public PrivacyRequestCreatedResponse submitExportRequest(PrivacyRequestSubmitRequest request) {
        return submitRequest(PrivacyRequestType.EXPORT, request);
    }

    public PrivacyRequestCreatedResponse submitDeleteRequest(PrivacyRequestSubmitRequest request) {
        return submitRequest(PrivacyRequestType.DELETE, request);
    }

    public PrivacyRequestDetailResponse getRequest(String requestId) {
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        RequestContext context = requireAuthenticatedContext();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");

        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND user_id = ? AND request_id = ? LIMIT 1",
                        tableName
                ),
                ps -> {
                    ps.setString(1, context.getProjectId());
                    ps.setString(2, context.getUserId());
                    ps.setString(3, normalizedRequestId);
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );

        if (row == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "未找到隐私请求", HttpStatus.NOT_FOUND);
        }

        return toDetailResponse(row);
    }

    public PrivacyRequestDetailResponse getLatestRequest() {
        RequestContext context = requireAuthenticatedContext();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");

        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND user_id = ? ORDER BY requested_at DESC LIMIT 1",
                        tableName
                ),
                ps -> {
                    ps.setString(1, context.getProjectId());
                    ps.setString(2, context.getUserId());
                },
                rs -> rs.next() ? mapStoredRow(rs) : null
        );

        if (row == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "当前用户暂无隐私请求记录", HttpStatus.NOT_FOUND);
        }

        return toDetailResponse(row);
    }

    private PrivacyRequestCreatedResponse submitRequest(PrivacyRequestType type, PrivacyRequestSubmitRequest request) {
        RequestContext context = requireAuthenticatedContext();
        Device device = context.getDevice();
        if (device == null || device.getDeviceId() == null) {
            throw new BusinessException("MISSING_DEVICE_ID", "请求上下文缺少 deviceId", HttpStatus.UNAUTHORIZED);
        }

        String requestId = generateRequestId();
        PrivacyProcessor processor = PrivacyProcessor.from(request.processor());
        String contactEmail = normalizeRequired(request.contactEmail(), 255, "contactEmail");
        String source = normalizeSource(request.source());
        String requesterNote = normalizeOptional(request.requesterNote(), 4000);
        String metadataJson = toJson(request.metadata());

        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String tableName = dataSourceManager.getTableName(context.getProjectId(), "privacy_requests");

        String sql = String.format(
                "INSERT INTO %s (request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                        "contact_email, requester_note, metadata, requested_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                tableName
        );

        int affected = jdbcTemplate.update(sql,
                requestId,
                context.getProjectId(),
                context.getUserId(),
                device.getDeviceId().toString(),
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

        emailService.sendPrivacyRequestSubmittedAlert(
                requestId,
                context.getProjectId(),
                context.getUserId(),
                type.name(),
                processor.name(),
                contactEmail,
                now
        );

        log.log(System.Logger.Level.INFO,
                "Privacy request submitted: projectId={0}, requestId={1}, type={2}, processor={3}",
                context.getProjectId(), requestId, type.name(), processor.name());

        return new PrivacyRequestCreatedResponse(
                requestId,
                type.name(),
                processor.name(),
                PrivacyRequestStatus.SUBMITTED.name(),
                now.toString(),
                contactEmail,
                "请求已创建，后台将人工处理并通过邮件反馈结果"
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
                toIso(row.updatedAt())
        );
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to parse JSON field", e);
            return null;
        }
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
}
