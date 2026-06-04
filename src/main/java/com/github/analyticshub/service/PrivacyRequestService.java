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
import java.util.LinkedHashMap;
import java.util.List;
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

        Object resultPayload = null;
        PrivacyRequestStatus responseStatus = PrivacyRequestStatus.SUBMITTED;
        String responseMessage = "请求已创建，后台将人工处理并通过邮件反馈结果";

        if (processor == PrivacyProcessor.ANALYTICSHUB) {
            if (type == PrivacyRequestType.EXPORT) {
                resultPayload = buildAnalyticsHubExportPayload(context, requestId, now);
                responseStatus = PrivacyRequestStatus.COMPLETED;
                responseMessage = "AnalyticsHub 统计数据已导出";
            } else if (type == PrivacyRequestType.DELETE) {
                resultPayload = deleteAnalyticsHubData(context, requestId, now);
                responseStatus = PrivacyRequestStatus.COMPLETED;
                responseMessage = "AnalyticsHub 统计数据已删除，设备采集凭证已撤销";
            }
            updateRequestResult(
                    jdbcTemplate,
                    tableName,
                    requestId,
                    context.getProjectId(),
                    responseStatus,
                    resultPayload,
                    now
            );
        } else if (processor == PrivacyProcessor.POSTHOG) {
            resultPayload = buildPostHogManualPayload(context, type);
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
                responseStatus.name(),
                now.toString(),
                contactEmail,
                responseMessage,
                resultPayload
        );
    }

    private Map<String, Object> buildAnalyticsHubExportPayload(RequestContext context, String requestId, Instant exportedAt) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String projectId = context.getProjectId();
        String userId = context.getUserId();
        String deviceId = context.getDevice().getDeviceId().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("processor", PrivacyProcessor.ANALYTICSHUB.name());
        payload.put("exportedAt", exportedAt.toString());
        payload.put("projectId", projectId);
        payload.put("userId", userId);
        payload.put("deviceId", deviceId);
        payload.put("device", firstOrNull(queryDeviceRows(jdbcTemplate, projectId, deviceId)));
        payload.put("events", queryEventRows(jdbcTemplate, projectId, userId, deviceId));
        payload.put("sessions", querySessionRows(jdbcTemplate, projectId, userId, deviceId));
        payload.put("trafficMetrics", queryTrafficMetricRows(jdbcTemplate, projectId, userId, deviceId));
        return payload;
    }

    private Map<String, Object> deleteAnalyticsHubData(RequestContext context, String requestId, Instant deletedAt) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String projectId = context.getProjectId();
        String userId = context.getUserId();
        String deviceId = context.getDevice().getDeviceId().toString();

        String eventsTable = dataSourceManager.getTableName(projectId, "events");
        String sessionsTable = dataSourceManager.getTableName(projectId, "sessions");
        String trafficTable = dataSourceManager.getTableName(projectId, "traffic_metrics");
        String devicesTable = dataSourceManager.getTableName(projectId, "devices");

        int eventsDeleted = jdbcTemplate.update(
                String.format("DELETE FROM %s WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)", eventsTable),
                projectId, userId, deviceId
        );
        int sessionsDeleted = jdbcTemplate.update(
                String.format("DELETE FROM %s WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)", sessionsTable),
                projectId, userId, deviceId
        );
        int trafficMetricsDeleted = jdbcTemplate.update(
                String.format("DELETE FROM %s WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)", trafficTable),
                projectId, userId, deviceId
        );
        int devicesDeleted = jdbcTemplate.update(
                String.format("DELETE FROM %s WHERE project_id = ? AND device_id = ?::uuid", devicesTable),
                projectId, deviceId
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("processor", PrivacyProcessor.ANALYTICSHUB.name());
        payload.put("deletedAt", deletedAt.toString());
        payload.put("projectId", projectId);
        payload.put("userId", userId);
        payload.put("deviceId", deviceId);
        payload.put("eventsDeleted", eventsDeleted);
        payload.put("sessionsDeleted", sessionsDeleted);
        payload.put("trafficMetricsDeleted", trafficMetricsDeleted);
        payload.put("devicesDeleted", devicesDeleted);
        return payload;
    }

    private Map<String, Object> buildPostHogManualPayload(RequestContext context, PrivacyRequestType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processor", PrivacyProcessor.POSTHOG.name());
        payload.put("manualActionRequired", true);
        payload.put("distinctId", context.getUserId());
        payload.put("deviceId", context.getDevice().getDeviceId().toString());
        if (type == PrivacyRequestType.DELETE) {
            payload.put("recommendedAction", "Find PostHog persons by distinct_id, bulk delete with delete_events=true, then verify deletion_status.");
        } else {
            payload.put("recommendedAction", "Find PostHog persons by distinct_id and export person properties plus related events through the Query API.");
        }
        return payload;
    }

    private void updateRequestResult(JdbcTemplate jdbcTemplate,
                                     String tableName,
                                     String requestId,
                                     String projectId,
                                     PrivacyRequestStatus status,
                                     Object resultPayload,
                                     Instant now) {
        String resultPayloadJson;
        try {
            resultPayloadJson = objectMapper.writeValueAsString(resultPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("resultPayload JSON 序列化失败");
        }

        int updated = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET status = ?, result_payload = ?::jsonb, processed_at = ?, closed_at = ?, updated_at = ? " +
                                "WHERE project_id = ? AND request_id = ?",
                        tableName
                ),
                status.name(),
                resultPayloadJson,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                projectId,
                requestId
        );
        if (updated != 1) {
            throw new BusinessException("PRIVACY_REQUEST_UPDATE_FAILED", "隐私请求处理结果写入失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<Map<String, Object>> queryDeviceRows(JdbcTemplate jdbcTemplate, String projectId, String deviceId) {
        String table = dataSourceManager.getTableName(projectId, "devices");
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT device_id::text AS device_id, device_model, os_version, app_version, project_id, is_banned, ban_reason, " +
                                "created_at, last_active_at FROM %s WHERE project_id = ? AND device_id = ?::uuid",
                        table
                ),
                projectId,
                deviceId
        ));
    }

    private List<Map<String, Object>> queryEventRows(JdbcTemplate jdbcTemplate, String projectId, String userId, String deviceId) {
        String table = dataSourceManager.getTableName(projectId, "events");
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT event_id, device_id::text AS device_id, user_id, session_id::text AS session_id, event_type, " +
                                "event_timestamp, properties::text AS properties, project_id, created_at FROM %s " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid) ORDER BY created_at ASC, id ASC",
                        table
                ),
                projectId,
                userId,
                deviceId
        ));
    }

    private List<Map<String, Object>> querySessionRows(JdbcTemplate jdbcTemplate, String projectId, String userId, String deviceId) {
        String table = dataSourceManager.getTableName(projectId, "sessions");
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT session_id::text AS session_id, device_id::text AS device_id, user_id, session_start_time, " +
                                "session_duration_ms, device_model, os_version, app_version, build_number, screen_count, event_count, " +
                                "project_id, created_at FROM %s WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid) " +
                                "ORDER BY created_at ASC, id ASC",
                        table
                ),
                projectId,
                userId,
                deviceId
        ));
    }

    private List<Map<String, Object>> queryTrafficMetricRows(JdbcTemplate jdbcTemplate, String projectId, String userId, String deviceId) {
        String table = dataSourceManager.getTableName(projectId, "traffic_metrics");
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT metric_id, device_id::text AS device_id, user_id, session_id::text AS session_id, metric_type, " +
                                "page_path, referrer, metric_timestamp, metadata::text AS metadata, project_id, created_at FROM %s " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid) ORDER BY created_at ASC, id ASC",
                        table
                ),
                projectId,
                userId,
                deviceId
        ));
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    row.forEach((key, value) -> normalized.put(key, normalizeDbValue(key, value)));
                    return normalized;
                })
                .toList();
    }

    private Object normalizeDbValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toInstant().toString();
        }
        if (("properties".equals(key) || "metadata".equals(key)) && value instanceof String rawJson) {
            return parseJson(rawJson);
        }
        return value;
    }

    private static Object firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
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
