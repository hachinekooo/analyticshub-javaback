package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminPrivacyExecutionRequest;
import com.github.analyticshub.dto.AdminPrivacyExecutionResponse;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestStatus;
import com.github.analyticshub.dto.PrivacyRequestType;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在客服明确确认后执行 AnalyticsHub 自有项目数据的导出或匿名化。
 *
 * <p>App 端只创建工单，本服务只通过 Admin API 调用。工单和不可变活动流作为
 * 合规审计记录保留；可识别的分析事实会按内置策略匿名化，而不是直接物理删除。
 * 部署方仍需结合其数据内容与适用法律评估匿名化效果和审计留存期限。</p>
 */
@Service
public class PrivacyDataExecutionService {

    private static final String COMPLETED = PrivacyRequestStatus.COMPLETED.name();
    private static final System.Logger log = System.getLogger(PrivacyDataExecutionService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ProjectTransactionExecutor projectTransactions;

    public PrivacyDataExecutionService(MultiDataSourceManager dataSourceManager,
                                       ObjectMapper objectMapper,
                                       ProjectTransactionExecutor projectTransactions) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.projectTransactions = projectTransactions;
    }

    public AdminPrivacyExecutionResponse execute(String projectId,
                                                 String requestId,
                                                 AdminPrivacyExecutionRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        String operator = normalizeRequired(request.operator(), 64, "operator");
        ProjectContext context = requireProject(normalizedProjectId);
        Tables tables = resolveTables(normalizedProjectId);

        AdminPrivacyExecutionResponse response = projectTransactions.execute(
                context.dataSource(),
                jdbcTemplate -> executeInTransaction(
                        jdbcTemplate,
                        tables,
                        normalizedProjectId,
                        normalizedRequestId,
                        operator,
                        request
                )
        );

        log.log(System.Logger.Level.INFO,
                "Privacy data operation completed: projectId={0}, requestId={1}, requestType={2}",
                normalizedProjectId, normalizedRequestId, response.requestType());
        return response;
    }

    private AdminPrivacyExecutionResponse executeInTransaction(JdbcTemplate jdbcTemplate,
                                                               Tables tables,
                                                               String projectId,
                                                               String requestId,
                                                               String operator,
                                                               AdminPrivacyExecutionRequest request) {
        StoredRequest workOrder = requireRequest(jdbcTemplate, tables.privacyRequests(), projectId, requestId);
        requireExpectedVersion(workOrder, request.version());
        requireExecutable(workOrder);

        PrivacyRequestType requestType = PrivacyRequestType.from(workOrder.requestType());
        if (requestType == PrivacyRequestType.DELETE
                && !requestId.equals(request.confirmation() == null ? "" : request.confirmation().trim())) {
            throw new BusinessException(
                    "PRIVACY_DELETE_CONFIRMATION_REQUIRED",
                    "删除请求必须输入完整工单号进行确认",
                    HttpStatus.BAD_REQUEST
            );
        }

        Instant now = Instant.now();
        Execution execution = requestType == PrivacyRequestType.EXPORT
                ? exportData(jdbcTemplate, tables, workOrder, now)
                : anonymizeData(jdbcTemplate, tables, workOrder, now);

        String summaryJson = toJson(execution.summary(), "executionSummary");
        int updated = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET status = ?, operator = ?, operator_note = ?, result_payload = ?::jsonb, " +
                                "processed_at = COALESCE(processed_at, ?), closed_at = ?, updated_at = ?, version = version + 1 " +
                                "WHERE project_id = ? AND request_id = ? AND version = ?",
                        tables.privacyRequests()
                ),
                COMPLETED,
                operator,
                requestType == PrivacyRequestType.EXPORT
                        ? "客服已生成数据导出文件"
                        : "客服已执行匿名化并撤销设备凭据",
                summaryJson,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                projectId,
                requestId,
                workOrder.version()
        );
        if (updated != 1) {
            throw versionConflict(workOrder.version());
        }

        appendActivity(
                jdbcTemplate,
                tables.activities(),
                projectId,
                requestId,
                requestType == PrivacyRequestType.EXPORT ? "DATA_EXPORT_GENERATED" : "DATA_ANONYMIZED",
                workOrder.status(),
                COMPLETED,
                operator,
                execution.summary(),
                now
        );

        return new AdminPrivacyExecutionResponse(
                requestId,
                requestType.name(),
                COMPLETED,
                now.toString(),
                workOrder.version() + 1,
                execution.downloadFileName(),
                execution.summary(),
                execution.exportData()
        );
    }

    private Execution exportData(JdbcTemplate jdbcTemplate,
                                 Tables tables,
                                 StoredRequest workOrder,
                                 Instant now) {
        List<Map<String, Object>> devices = queryDevices(jdbcTemplate, tables.devices(), workOrder);
        List<Map<String, Object>> events = queryEvents(jdbcTemplate, tables.events(), workOrder);
        List<Map<String, Object>> sessions = querySessions(jdbcTemplate, tables.sessions(), workOrder);
        List<Map<String, Object>> trafficMetrics = queryTraffic(jdbcTemplate, tables.trafficMetrics(), workOrder);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("devices", devices.size());
        counts.put("events", events.size());
        counts.put("sessions", sessions.size());
        counts.put("trafficMetrics", trafficMetrics.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operation", "EXPORT");
        summary.put("executedAt", now.toString());
        summary.put("counts", counts);
        summary.put("credentialsExcluded", true);

        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("userId", workOrder.userId());
        subject.put("deviceId", workOrder.deviceId());
        subject.put("contactEmail", workOrder.contactEmail());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", 1);
        data.put("requestId", workOrder.requestId());
        data.put("projectId", workOrder.projectId());
        data.put("exportedAt", now.toString());
        data.put("subject", subject);
        data.put("devices", devices);
        data.put("events", events);
        data.put("sessions", sessions);
        data.put("trafficMetrics", trafficMetrics);

        return new Execution(
                summary,
                data,
                "privacy-export-" + safeFilePart(workOrder.requestId()) + ".json"
        );
    }

    private Execution anonymizeData(JdbcTemplate jdbcTemplate,
                                    Tables tables,
                                    StoredRequest workOrder,
                                    Instant now) {
        String anonymousUserId = "anon_" + UUID.randomUUID().toString().replace("-", "");
        String anonymousDeviceId = UUID.randomUUID().toString();
        String recordSalt = UUID.randomUUID().toString();

        int credentialsRevoked = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET device_id = ?::uuid, api_key = ?, secret_key = ?, " +
                                "previous_api_key = NULL, previous_secret_key = NULL, previous_credentials_expires_at = NULL, " +
                                "device_model = NULL, os_version = NULL, is_banned = TRUE, " +
                                "ban_reason = 'PRIVACY_REQUEST_COMPLETED', " +
                                "created_at = date_trunc('day', created_at), " +
                                "last_active_at = date_trunc('day', ?::timestamptz) " +
                                "WHERE project_id = ? AND device_id = ?::uuid",
                        tables.devices()
                ),
                anonymousDeviceId,
                "revoked_" + UUID.randomUUID(),
                "revoked_" + UUID.randomUUID(),
                Timestamp.from(now),
                workOrder.projectId(),
                workOrder.deviceId()
        );

        int idempotencyKeysAnonymized = jdbcTemplate.update(
                String.format(
                        "UPDATE %s i SET event_id = 'anon_' || md5(? || i.event_id) " +
                                "WHERE i.project_id = ? AND EXISTS (SELECT 1 FROM %s e " +
                                "WHERE e.project_id = ? AND e.event_id = i.event_id " +
                                "AND (e.user_id = ? OR e.device_id = ?::uuid))",
                        tables.idempotencyKeys(),
                        tables.events()
                ),
                recordSalt,
                workOrder.projectId(),
                workOrder.projectId(),
                workOrder.userId(),
                workOrder.deviceId()
        );

        int eventsAnonymized = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET event_id = 'anon_' || md5(? || event_id), user_id = ?, device_id = ?::uuid, " +
                                "session_id = NULL, properties = '{}'::jsonb, " +
                                "event_timestamp = (event_timestamp / 86400000) * 86400000, " +
                                "created_at = date_trunc('day', created_at) " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)",
                        tables.events()
                ),
                recordSalt,
                anonymousUserId,
                anonymousDeviceId,
                workOrder.projectId(),
                workOrder.userId(),
                workOrder.deviceId()
        );

        int sessionsAnonymized = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET session_id = md5(? || session_id::text)::uuid, user_id = ?, device_id = ?::uuid, " +
                                "device_model = NULL, os_version = NULL, " +
                                "session_start_time = date_trunc('day', session_start_time), " +
                                "created_at = date_trunc('day', created_at) " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)",
                        tables.sessions()
                ),
                recordSalt,
                anonymousUserId,
                anonymousDeviceId,
                workOrder.projectId(),
                workOrder.userId(),
                workOrder.deviceId()
        );

        int trafficMetricsAnonymized = jdbcTemplate.update(
                String.format(
                        "UPDATE %s SET metric_id = 'anon_' || md5(? || metric_id), user_id = ?, device_id = ?::uuid, " +
                                "session_id = NULL, page_path = NULL, referrer = NULL, metadata = '{}'::jsonb, " +
                                "metric_timestamp = (metric_timestamp / 86400000) * 86400000, " +
                                "created_at = date_trunc('day', created_at) " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid)",
                        tables.trafficMetrics()
                ),
                recordSalt,
                anonymousUserId,
                anonymousDeviceId,
                workOrder.projectId(),
                workOrder.userId(),
                workOrder.deviceId()
        );

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deviceCredentialsRevoked", credentialsRevoked);
        counts.put("idempotencyKeysAnonymized", idempotencyKeysAnonymized);
        counts.put("eventsAnonymized", eventsAnonymized);
        counts.put("sessionsAnonymized", sessionsAnonymized);
        counts.put("trafficMetricsAnonymized", trafficMetricsAnonymized);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operation", "ANONYMIZE");
        summary.put("executedAt", now.toString());
        summary.put("counts", counts);
        summary.put("analyticsDirectIdentifiersRemoved", true);
        summary.put("credentialsRevoked", true);
        summary.put("retainedData", List.of(
                "ANONYMIZED_ANALYTICS",
                "AGGREGATED_COUNTERS",
                "PRIVACY_WORK_ORDER_AUDIT"
        ));

        return new Execution(summary, null, null);
    }

    private List<Map<String, Object>> queryDevices(JdbcTemplate jdbcTemplate,
                                                   String table,
                                                   StoredRequest request) {
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT device_id::text AS device_id, device_model, os_version, app_version, " +
                                "is_banned, ban_reason, created_at, last_active_at FROM %s " +
                                "WHERE project_id = ? AND device_id = ?::uuid",
                        table
                ),
                request.projectId(),
                request.deviceId()
        ));
    }

    private List<Map<String, Object>> queryEvents(JdbcTemplate jdbcTemplate,
                                                  String table,
                                                  StoredRequest request) {
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT event_id, device_id::text AS device_id, user_id, session_id::text AS session_id, " +
                                "event_type, event_timestamp, properties::text AS properties_json, created_at FROM %s " +
                                "WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid) ORDER BY created_at, id",
                        table
                ),
                request.projectId(),
                request.userId(),
                request.deviceId()
        ));
    }

    private List<Map<String, Object>> querySessions(JdbcTemplate jdbcTemplate,
                                                    String table,
                                                    StoredRequest request) {
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT session_id::text AS session_id, device_id::text AS device_id, user_id, session_start_time, " +
                                "session_duration_ms, device_model, os_version, app_version, build_number, screen_count, " +
                                "event_count, created_at FROM %s WHERE project_id = ? " +
                                "AND (user_id = ? OR device_id = ?::uuid) ORDER BY created_at, id",
                        table
                ),
                request.projectId(),
                request.userId(),
                request.deviceId()
        ));
    }

    private List<Map<String, Object>> queryTraffic(JdbcTemplate jdbcTemplate,
                                                   String table,
                                                   StoredRequest request) {
        return normalizeRows(jdbcTemplate.queryForList(
                String.format(
                        "SELECT metric_id, device_id::text AS device_id, user_id, session_id::text AS session_id, " +
                                "metric_type, page_path, referrer, metric_timestamp, metadata::text AS metadata_json, " +
                                "created_at FROM %s WHERE project_id = ? AND (user_id = ? OR device_id = ?::uuid) " +
                                "ORDER BY created_at, id",
                        table
                ),
                request.projectId(),
                request.userId(),
                request.deviceId()
        ));
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> {
                String normalizedKey = key.endsWith("_json")
                        ? key.substring(0, key.length() - "_json".length())
                        : key;
                normalized.put(normalizedKey, normalizeDbValue(key, value));
            });
            return normalized;
        }).toList();
    }

    private Object normalizeDbValue(String key, Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (key.endsWith("_json") && value instanceof String rawJson) {
            try {
                return objectMapper.readValue(rawJson, Object.class);
            } catch (Exception exception) {
                throw new IllegalArgumentException("项目数据中的 JSON 字段无法解析", exception);
            }
        }
        return value;
    }

    private StoredRequest requireRequest(JdbcTemplate jdbcTemplate,
                                         String table,
                                         String projectId,
                                         String requestId) {
        StoredRequest request = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, status, " +
                                "contact_email, version FROM %s WHERE project_id = ? AND request_id = ? FOR UPDATE",
                        table
                ),
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, requestId);
                },
                rs -> rs.next() ? mapRequest(rs) : null
        );
        if (request == null) {
            throw new BusinessException("PRIVACY_REQUEST_NOT_FOUND", "未找到隐私请求", HttpStatus.NOT_FOUND);
        }
        return request;
    }

    private static StoredRequest mapRequest(ResultSet rs) throws java.sql.SQLException {
        return new StoredRequest(
                rs.getString("request_id"),
                rs.getString("project_id"),
                rs.getString("user_id"),
                rs.getString("device_id"),
                rs.getString("request_type"),
                rs.getString("processor"),
                rs.getString("status"),
                rs.getString("contact_email"),
                rs.getLong("version")
        );
    }

    private static void requireExpectedVersion(StoredRequest request, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != request.version()) {
            throw versionConflict(request.version());
        }
    }

    private static void requireExecutable(StoredRequest request) {
        if (PrivacyProcessor.from(request.processor()) != PrivacyProcessor.ANALYTICSHUB) {
            throw new BusinessException(
                    "PRIVACY_PROCESSOR_NOT_EXECUTABLE",
                    "该工单不属于 AnalyticsHub 项目数据，不能使用内置执行器",
                    HttpStatus.CONFLICT
            );
        }
        if (PrivacyRequestStatus.from(request.status()).isFinalStatus()) {
            throw new BusinessException(
                    "PRIVACY_REQUEST_ALREADY_CLOSED",
                    "工单已结束，不能重复执行数据操作",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void appendActivity(JdbcTemplate jdbcTemplate,
                                String table,
                                String projectId,
                                String requestId,
                                String activityType,
                                String fromStatus,
                                String toStatus,
                                String actor,
                                Map<String, Object> details,
                                Instant now) {
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (activity_id, project_id, work_order_type, work_order_id, activity_type, " +
                                "from_status, to_status, actor, details, created_at) " +
                                "VALUES (?, ?, 'PRIVACY_REQUEST', ?, ?, ?, ?, ?, ?::jsonb, ?)",
                        table
                ),
                UUID.randomUUID().toString(),
                projectId,
                requestId,
                activityType,
                fromStatus,
                toStatus,
                actor,
                toJson(details, "activityDetails"),
                Timestamp.from(now)
        );
    }

    private String toJson(Map<String, Object> payload, String fieldName) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException(fieldName + " JSON 序列化失败", exception);
        }
    }

    private Tables resolveTables(String projectId) {
        return new Tables(
                dataSourceManager.getTableName(projectId, "privacy_requests"),
                dataSourceManager.getTableName(projectId, "work_order_activities"),
                dataSourceManager.getTableName(projectId, "devices"),
                dataSourceManager.getTableName(projectId, "events"),
                dataSourceManager.getTableName(projectId, "sessions"),
                dataSourceManager.getTableName(projectId, "traffic_metrics"),
                dataSourceManager.getTableName(projectId, "idempotency_keys")
        );
    }

    private ProjectContext requireProject(String projectId) {
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
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
            return new ProjectContext(dataSourceManager.getDataSource(projectId));
        } catch (Exception exception) {
            throw BusinessException.projectDbUnavailable(projectId);
        }
    }

    private static BusinessException versionConflict(long currentVersion) {
        return new BusinessException(
                "PRIVACY_REQUEST_VERSION_CONFLICT",
                "工单版本冲突，请刷新后重试（当前版本 " + currentVersion + "）",
                HttpStatus.CONFLICT
        );
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
            if (!Character.isWhitespace(c)
                    && !Character.isSpaceChar(c)
                    && Character.getType(c) != Character.FORMAT) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record ProjectContext(DataSource dataSource) {
    }

    private record Tables(
            String privacyRequests,
            String activities,
            String devices,
            String events,
            String sessions,
            String trafficMetrics,
            String idempotencyKeys
    ) {
    }

    private record StoredRequest(
            String requestId,
            String projectId,
            String userId,
            String deviceId,
            String requestType,
            String processor,
            String status,
            String contactEmail,
            long version
    ) {
    }

    private record Execution(
            Map<String, Object> summary,
            Map<String, Object> exportData,
            String downloadFileName
    ) {
    }
}
