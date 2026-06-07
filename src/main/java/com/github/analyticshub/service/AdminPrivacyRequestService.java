package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminPrivacyNotifyRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestItem;
import com.github.analyticshub.dto.AdminPrivacyRequestUpdateRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestsResponse;
import com.github.analyticshub.dto.PrivacyProcessor;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.dto.PrivacyRequestStatus;
import com.github.analyticshub.dto.PrivacyRequestType;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminPrivacyRequestService {

    private static final System.Logger log = System.getLogger(AdminPrivacyRequestService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;

    public AdminPrivacyRequestService(MultiDataSourceManager dataSourceManager,
                                      ObjectMapper objectMapper,
                                      EmailService emailService) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.emailService = emailService;
    }

    public AdminPrivacyRequestsResponse listRequests(String projectId,
                                                     String from,
                                                     String to,
                                                     Integer page,
                                                     Integer pageSize,
                                                     String status,
                                                     String requestType,
                                                     String processor,
                                                     String userId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        AdminQueryUtils.Paging paging = AdminQueryUtils.resolvePaging(page, pageSize);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String tableName = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");

        PrivacyRequestStatus statusFilter = parseStatusNullable(status);
        PrivacyRequestType typeFilter = parseTypeNullable(requestType);
        PrivacyProcessor processorFilter = parseProcessorNullable(processor);

        StringBuilder where = new StringBuilder(" WHERE project_id = ? AND requested_at >= ? AND requested_at < ? ");
        List<Object> args = new ArrayList<>();
        args.add(normalizedProjectId);
        args.add(Timestamp.from(range.start()));
        args.add(Timestamp.from(range.end()));

        if (statusFilter != null) {
            where.append(" AND status = ? ");
            args.add(statusFilter.name());
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
                "SELECT request_id, user_id, device_id, request_type, processor, status, contact_email, requested_at, processed_at, closed_at, operator " +
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
                        rs.getString("operator")
                ),
                listArgs.toArray()
        );

        return new AdminPrivacyRequestsResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                paging.page(),
                paging.pageSize(),
                totalValue,
                items
        );
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
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String tableName = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");

        StoredPrivacyRequest existing = requireRequest(normalizedProjectId, normalizedRequestId);

        PrivacyRequestStatus targetStatus = PrivacyRequestStatus.from(request.status());

        String nextOperator = mergeText(existing.operator(), request.operator(), 64);
        String nextOperatorNote = mergeText(existing.operatorNote(), request.operatorNote(), 4000);

        String resultPayloadJson = existing.resultPayloadText();
        if (request.resultPayload() != null) {
            resultPayloadJson = toJson(request.resultPayload(), "resultPayload");
        }

        Instant now = Instant.now();
        Instant nextProcessedAt = existing.processedAt();
        if (nextProcessedAt == null && targetStatus != PrivacyRequestStatus.SUBMITTED) {
            nextProcessedAt = now;
        }

        Instant nextClosedAt = targetStatus.isFinalStatus() ? now : null;

        String updateSql = String.format(
                "UPDATE %s SET status = ?, operator = ?, operator_note = ?, result_payload = ?::jsonb, " +
                        "processed_at = ?, closed_at = ?, updated_at = ? WHERE project_id = ? AND request_id = ?",
                tableName
        );

        int affected = jdbcTemplate.update(updateSql,
                targetStatus.name(),
                nextOperator,
                nextOperatorNote,
                resultPayloadJson,
                toTimestamp(nextProcessedAt),
                toTimestamp(nextClosedAt),
                Timestamp.from(now),
                normalizedProjectId,
                normalizedRequestId
        );

        if (affected != 1) {
            throw new BusinessException("PRIVACY_REQUEST_UPDATE_FAILED", "隐私请求更新失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (Boolean.TRUE.equals(request.notifyUser())) {
            String contactEmail = normalizeRequired(existing.contactEmail(), 255, "contactEmail");
            String message = request.notificationMessage();
            if (message == null || message.isBlank()) {
                message = defaultNotificationMessage(normalizedRequestId, targetStatus);
            }
            emailService.sendPrivacyUserNotification(
                    contactEmail,
                    "[Analytics Hub] Privacy Request " + normalizedRequestId + " " + targetStatus.name(),
                    message.trim()
            );
        }

        log.log(System.Logger.Level.INFO,
                "Privacy request updated: projectId={0}, requestId={1}, status={2}",
                normalizedProjectId, normalizedRequestId, targetStatus.name());

        return getRequestDetail(normalizedProjectId, normalizedRequestId);
    }

    public Map<String, String> notifyUser(String projectId,
                                          String requestId,
                                          AdminPrivacyNotifyRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedRequestId = normalizeRequired(requestId, 64, "requestId");
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String tableName = dataSourceManager.getTableName(normalizedProjectId, "privacy_requests");

        StoredPrivacyRequest existing = requireRequest(normalizedProjectId, normalizedRequestId);

        String contactEmail = normalizeRequired(existing.contactEmail(), 255, "contactEmail");
        String subject = normalizeRequired(request.subject(), 120, "subject");
        String message = normalizeRequired(request.message(), 4000, "message");

        emailService.sendPrivacyUserNotification(contactEmail, subject, message);

        String operator = mergeText(existing.operator(), request.operator(), 64);
        String operatorNote = mergeText(
                existing.operatorNote(),
                "[manual-notify] " + Instant.now() + " " + subject,
                4000
        );

        int affected = jdbcTemplate.update(
                String.format("UPDATE %s SET operator = ?, operator_note = ?, updated_at = ? WHERE project_id = ? AND request_id = ?", tableName),
                operator,
                operatorNote,
                Timestamp.from(Instant.now()),
                normalizedProjectId,
                normalizedRequestId
        );

        if (affected != 1) {
            throw new BusinessException("PRIVACY_NOTIFY_UPDATE_FAILED", "通知记录写入失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return Map.of(
                "requestId", normalizedRequestId,
                "status", "NOTIFIED"
        );
    }

    private StoredPrivacyRequest requireRequest(String projectId, String requestId) {
        ProjectContext context = requireProject(projectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String tableName = dataSourceManager.getTableName(projectId, "privacy_requests");

        StoredPrivacyRequest row = jdbcTemplate.query(
                String.format(
                        "SELECT request_id, project_id, user_id, device_id, request_type, processor, source, status, " +
                                "contact_email, requester_note, operator, operator_note, result_payload::text AS result_payload_text, " +
                                "metadata::text AS metadata_text, requested_at, processed_at, closed_at, updated_at " +
                                "FROM %s WHERE project_id = ? AND request_id = ? LIMIT 1",
                        tableName
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
            Instant updatedAt
    ) {
    }
}
