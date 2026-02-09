package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TrafficMetricService {

    private static final System.Logger log = System.getLogger(TrafficMetricService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final String ipHashSalt;

    public TrafficMetricService(MultiDataSourceManager dataSourceManager,
                                ObjectMapper objectMapper,
                                @Value("${app.traffic.ip-hash-salt:}") String ipHashSalt) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.ipHashSalt = ipHashSalt == null ? "" : ipHashSalt;
    }

    @Transactional
    public TrafficMetricTrackResponse track(TrafficMetricTrackRequest request, String clientIp, String userAgent) {
        RequestContext context = RequestContext.get();
        return trackInternal(
                context.getProjectId(),
                context.getDataSource(),
                context.getDevice().getDeviceId(),
                context.getUserId(),
                request,
                clientIp,
                userAgent
        );
    }

    @Transactional
    public int trackBatch(TrafficMetricTrackRequest[] items, String clientIp, String userAgent) {
        if (items == null || items.length == 0) {
            return 0;
        }
        RequestContext context = RequestContext.get();
        return trackBatchInternal(
                context.getProjectId(),
                context.getDataSource(),
                context.getDevice().getDeviceId(),
                context.getUserId(),
                items,
                clientIp,
                userAgent
        );
    }

    @Transactional
    public TrafficMetricTrackResponse trackPublic(String projectId, UUID deviceId, String userId,
                                                  TrafficMetricTrackRequest request, String clientIp, String userAgent) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        String resolvedUserId = (userId == null || userId.isBlank())
                ? deriveUserId(deviceId)
                : userId.trim();
        return trackInternal(normalizedProjectId, context.dataSource(), deviceId, resolvedUserId, request, clientIp, userAgent);
    }

    @Transactional
    public int trackPublicBatch(String projectId, UUID deviceId, String userId,
                                TrafficMetricTrackRequest[] items, String clientIp, String userAgent) {
        if (items == null || items.length == 0) {
            return 0;
        }
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        String resolvedUserId = (userId == null || userId.isBlank())
                ? deriveUserId(deviceId)
                : userId.trim();
        return trackBatchInternal(normalizedProjectId, context.dataSource(), deviceId, resolvedUserId, items, clientIp, userAgent);
    }

    private TrafficMetricTrackResponse trackInternal(String projectId,
                                                     DataSource dataSource,
                                                     UUID deviceId,
                                                     String userId,
                                                     TrafficMetricTrackRequest request,
                                                     String clientIp,
                                                     String userAgent) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.metricType() == null || request.metricType().isBlank()) {
            throw new BusinessException("MISSING_METRIC_TYPE", "缺少 metricType");
        }
        String metricType = normalizeMetricType(request.metricType());
        if (metricType.isBlank()) {
            throw new BusinessException("MISSING_METRIC_TYPE", "缺少 metricType");
        }

        String metricId = CryptoUtils.generateTrafficMetricId();
        long timestamp = (request.timestamp() == null || request.timestamp() <= 0)
                ? System.currentTimeMillis()
                : request.timestamp();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String table = dataSourceManager.getTableName(projectId, "traffic_metrics");

        String insertSql = String.format(
                "INSERT INTO %s (metric_id, device_id, user_id, session_id, metric_type, page_path, referrer, metric_timestamp, metadata, project_id, created_at) " +
                        "VALUES (?, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                table
        );

        String metadataJson = null;
        try {
            JsonNode enriched = enrichMetadata(projectId, request.metadata(), clientIp, userAgent);
            metadataJson = enriched == null ? null : objectMapper.writeValueAsString(enriched);
        } catch (Exception e) {
            log.log(System.Logger.Level.DEBUG, "Failed to serialize metadata", e);
        }

        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                    insertSql,
                    metricId,
                    deviceId.toString(),
                    userId,
                    request.sessionId() == null ? null : request.sessionId().toString(),
                    metricType,
                    trimToNull(request.pagePath()),
                    trimToNull(request.referrer()),
                    timestamp,
                    metadataJson,
                    projectId,
                    Timestamp.from(now)
            );
        } catch (DataAccessException e) {
            throw BusinessException.projectDbUnavailable(projectId);
        }

        return new TrafficMetricTrackResponse(metricId);
    }

    private int trackBatchInternal(String projectId,
                                   DataSource dataSource,
                                   UUID deviceId,
                                   String userId,
                                   TrafficMetricTrackRequest[] items,
                                   String clientIp,
                                   String userAgent) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String table = dataSourceManager.getTableName(projectId, "traffic_metrics");

        StringBuilder valuesSql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int accepted = 0;

        for (TrafficMetricTrackRequest request : items) {
            if (request == null || request.metricType() == null || request.metricType().isBlank()) {
                continue;
            }
            String metricType = normalizeMetricType(request.metricType());
            if (metricType.isBlank()) {
                continue;
            }

            String metricId = CryptoUtils.generateTrafficMetricId();
            long timestamp = (request.timestamp() == null || request.timestamp() <= 0)
                    ? System.currentTimeMillis()
                    : request.timestamp();

            String metadataJson = null;
            try {
                JsonNode enriched = enrichMetadata(projectId, request.metadata(), clientIp, userAgent);
                metadataJson = enriched == null ? null : objectMapper.writeValueAsString(enriched);
            } catch (Exception e) {
                continue;
            }

            if (accepted > 0) {
                valuesSql.append(", ");
            }
            valuesSql.append("(?, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?)");

            args.add(metricId);
            args.add(deviceId.toString());
            args.add(userId);
            args.add(request.sessionId() == null ? null : request.sessionId().toString());
            args.add(metricType);
            args.add(trimToNull(request.pagePath()));
            args.add(trimToNull(request.referrer()));
            args.add(timestamp);
            args.add(metadataJson);
            args.add(projectId);
            args.add(Timestamp.from(Instant.now()));
            accepted++;
        }

        if (accepted == 0) {
            return 0;
        }

        String insertSql = String.format(
                "INSERT INTO %s (metric_id, device_id, user_id, session_id, metric_type, page_path, referrer, metric_timestamp, metadata, project_id, created_at) VALUES %s",
                table,
                valuesSql
        );
        try {
            jdbcTemplate.update(insertSql, args.toArray());
        } catch (DataAccessException e) {
            throw BusinessException.projectDbUnavailable(projectId);
        }
        return accepted;
    }

    private JsonNode enrichMetadata(String projectId, JsonNode metadata, String clientIp, String userAgent) {
        if (metadata == null || metadata.isNull()) {
            ObjectNode node = objectMapper.createObjectNode();
            writeClientMeta(node, projectId, clientIp, userAgent);
            return node;
        }
        if (metadata.isObject()) {
            ObjectNode node = (ObjectNode) metadata.deepCopy();
            writeClientMeta(node, projectId, clientIp, userAgent);
            return node;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("data", metadata);
        writeClientMeta(node, projectId, clientIp, userAgent);
        return node;
    }

    private void writeClientMeta(ObjectNode node, String projectId, String clientIp, String userAgent) {
        if (!ipHashSalt.isBlank() && clientIp != null && !clientIp.isBlank()) {
            node.put("ipHash", CryptoUtils.sha256Hex(ipHashSalt + "|" + clientIp));
        }
        if (userAgent != null && !userAgent.isBlank()) {
            node.put("userAgent", userAgent);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String deriveUserId(UUID deviceId) {
        String hex = CryptoUtils.sha256Hex(deviceId.toString().toLowerCase(Locale.ROOT));
        return hex.substring(0, 32);
    }

    private static String normalizeMetricType(String metricType) {
        if (metricType == null) {
            return "";
        }
        String input = metricType.trim();
        if (input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 8);
        char prevOut = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '-' || c == ' ') {
                if (prevOut != '_' && prevOut != 0) {
                    out.append('_');
                    prevOut = '_';
                }
                continue;
            }
            if (c == '_') {
                if (prevOut != '_' && prevOut != 0) {
                    out.append('_');
                    prevOut = '_';
                }
                continue;
            }
            if (Character.isUpperCase(c)) {
                if (prevOut != '_' && prevOut != 0 && Character.isLowerCase(prevOut)) {
                    out.append('_');
                }
                char lowered = Character.toLowerCase(c);
                out.append(lowered);
                prevOut = lowered;
                continue;
            }
            char lowered = Character.toLowerCase(c);
            out.append(lowered);
            prevOut = lowered;
        }
        String normalized = out.toString();
        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals("pageview")) {
            return "page_view";
        }
        if (normalized.equals("page_view")) {
            return "page_view";
        }
        return normalized;
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

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

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
}
