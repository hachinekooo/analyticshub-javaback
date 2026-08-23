package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminEventRecord;
import com.github.analyticshub.dto.AdminEventsResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理端事件列表查询服务。
 *
 * <p>用户旅程与大属性读取由 {@link AdminEventJourneyService} 负责，避免普通分页查询
 * 承担旅程窗口、属性预算等独立策略。</p>
 */
@Service
public class AdminEventQueryService {

    private static final System.Logger log = System.getLogger(AdminEventQueryService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ActorIdentityResolver actorIdentityResolver;

    public AdminEventQueryService(MultiDataSourceManager dataSourceManager,
                                  ObjectMapper objectMapper,
                                  ActorIdentityResolver actorIdentityResolver) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.actorIdentityResolver = actorIdentityResolver;
    }

    public AdminEventsResponse listEvents(String projectId, String from, String to,
                                          Integer page, Integer pageSize,
                                          String eventType, String userId,
                                          String resolvedActorId, String deviceId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        AdminQueryUtils.Paging paging = AdminQueryUtils.resolvePaging(page, pageSize);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        String actorLinksTable = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");

        if (deviceId != null && !deviceId.isBlank() && !CryptoUtils.isValidUUID(deviceId)) {
            throw new IllegalArgumentException("deviceId 格式无效");
        }
        List<String> resolvedActorMembers = resolveActorMembers(
                jdbcTemplate,
                actorLinksTable,
                normalizedProjectId,
                resolvedActorId
        );

        StringBuilder where = new StringBuilder(
                " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
        );
        List<Object> args = new ArrayList<>();
        args.add(normalizedProjectId);
        args.add(range.start().toEpochMilli());
        args.add(range.end().toEpochMilli());

        if (eventType != null && !eventType.isBlank()) {
            where.append(" AND event_type = ? ");
            args.add(eventType.trim());
        }
        if (userId != null && !userId.isBlank()) {
            where.append(" AND user_id = ? ");
            args.add(userId.trim());
        }
        if (!resolvedActorMembers.isEmpty()) {
            where.append(" AND lower(user_id) IN (")
                    .append(String.join(",", resolvedActorMembers.stream().map(ignored -> "?").toList()))
                    .append(") ");
            args.addAll(resolvedActorMembers);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            where.append(" AND device_id = ?::uuid ");
            args.add(deviceId.trim());
        }

        String countSql = String.format("SELECT COUNT(*) FROM %s %s", eventsTable, where);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());
        long totalValue = total == null ? 0L : total;

        String listSql = String.format(
                "SELECT event_id, event_type, event_timestamp, created_at, device_id, user_id, session_id, properties " +
                        "FROM %s %s ORDER BY event_timestamp DESC, id DESC LIMIT ? OFFSET ?",
                eventsTable,
                where
        );

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(paging.pageSize());
        listArgs.add(paging.offset());

        List<RawAdminEventRecord> rawItems = jdbcTemplate.query(
                listSql,
                (rs, rowNum) -> readRawEventRecord(rs),
                listArgs.toArray()
        );
        List<AdminEventRecord> items = resolveRecords(
                jdbcTemplate, actorLinksTable, normalizedProjectId, rawItems
        );

        return new AdminEventsResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                paging.page(),
                paging.pageSize(),
                totalValue,
                items
        );
    }

    private RawAdminEventRecord readRawEventRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RawAdminEventRecord(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getLong("event_timestamp"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("device_id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                parseProperties(rs.getString("properties"))
        );
    }

    private JsonNode parseProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(properties);
        } catch (Exception exception) {
            log.log(System.Logger.Level.WARNING, "Failed to parse properties JSON", exception);
            return null;
        }
    }

    private List<AdminEventRecord> resolveRecords(JdbcTemplate jdbcTemplate,
                                                   String actorLinksTable,
                                                   String projectId,
                                                   List<RawAdminEventRecord> rawItems) {
        Map<String, String> resolvedActors = actorIdentityResolver.resolveCanonicalActors(
                jdbcTemplate,
                actorLinksTable,
                projectId,
                rawItems.stream().map(RawAdminEventRecord::userId).filter(Objects::nonNull).toList()
        );
        return rawItems.stream().map(item -> {
            String resolvedActor = item.userId() == null
                    ? null
                    : resolvedActors.getOrDefault(item.userId(), item.userId());
            String identityScope = textProperty(item.properties(), "identity_scope");
            return new AdminEventRecord(
                    item.eventId(),
                    item.eventType(),
                    item.eventTimestamp(),
                    item.createdAt(),
                    item.deviceId(),
                    item.userId(),
                    resolvedActor,
                    identityScope,
                    item.userId() != null
                            && !actorIdentityResolver.representsSameActor(item.userId(), resolvedActor),
                    item.sessionId(),
                    item.properties()
            );
        }).toList();
    }

    private List<String> resolveActorMembers(JdbcTemplate jdbcTemplate,
                                             String actorLinksTable,
                                             String projectId,
                                             String resolvedActorId) {
        if (resolvedActorId == null || resolvedActorId.isBlank()) {
            return List.of();
        }
        if (!CryptoUtils.isValidUUID(resolvedActorId.trim())) {
            throw new IllegalArgumentException("resolvedActorId 格式无效");
        }
        return actorIdentityResolver.resolveActorMembers(
                jdbcTemplate,
                actorLinksTable,
                projectId,
                resolvedActorId
        );
    }

    private static String textProperty(JsonNode properties, String key) {
        if (properties == null || !properties.isObject()) {
            return null;
        }
        JsonNode value = properties.get(key);
        return value == null || !value.isString() ? null : value.asString();
    }

    private record RawAdminEventRecord(
            String eventId,
            String eventType,
            Long eventTimestamp,
            String createdAt,
            String deviceId,
            String userId,
            String sessionId,
            JsonNode properties
    ) {}

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (normalizedProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        } catch (IllegalArgumentException e) {
            logDebug("Invalid projectId", normalizedProjectId, e);
            throw BusinessException.invalidProject(normalizedProjectId);
        } catch (Exception e) {
            logDebug("Failed to load project config", normalizedProjectId, e);
            throw BusinessException.invalidProject(normalizedProjectId);
        }

        if (projectConfig == null) {
            logDebug("Project config is null", normalizedProjectId, null);
            throw BusinessException.invalidProject(normalizedProjectId);
        }
        if (!Boolean.TRUE.equals(projectConfig.isActive())) {
            throw BusinessException.projectInactive();
        }

        try {
            DataSource dataSource = dataSourceManager.getDataSource(normalizedProjectId);
            return new ProjectContext(projectConfig, dataSource);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Project datasource unavailable: {0}", normalizedProjectId);
            logDebug("Project datasource unavailable", normalizedProjectId, e);
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

    private static void logDebug(String message, String projectId, Exception e) {
        System.Logger logger = System.getLogger(AdminEventQueryService.class.getName());
        if (e == null) {
            logger.log(System.Logger.Level.DEBUG, "{0}: projectId={1}", message, debugValue(projectId));
        } else {
            logger.log(System.Logger.Level.DEBUG, "{0}: projectId={1}, error={2}", message, debugValue(projectId), e.getMessage());
        }
    }

    private static String debugValue(String value) {
        if (value == null) {
            return "len=0 hex=<null>";
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%04x", (int) value.charAt(i)));
        }
        return "len=" + value.length() + " hex=" + hex;
    }
}
