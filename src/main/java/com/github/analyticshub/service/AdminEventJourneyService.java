package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminEventJourneyResponse;
import com.github.analyticshub.dto.AdminEventPropertiesResponse;
import com.github.analyticshub.dto.AdminEventRecord;
import com.github.analyticshub.dto.AdminJourneyEventRecord;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端锚点用户旅程查询。
 *
 * <p>本服务拥有时间窗、候选选择、响应属性预算和单事件属性加载契约；普通事件列表的
 * 分页与筛选继续由 {@link AdminEventQueryService} 负责。</p>
 */
@Service
public class AdminEventJourneyService {

    private static final System.Logger log = System.getLogger(AdminEventJourneyService.class.getName());
    private static final int DEFAULT_JOURNEY_WINDOW_MINUTES = 60;
    private static final int MAX_JOURNEY_WINDOW_MINUTES = 7 * 24 * 60;
    private static final int JOURNEY_EVENT_LIMIT = 200;
    private static final int JOURNEY_PROPERTY_PER_EVENT_BYTES = 64 * 1024;
    private static final int JOURNEY_PROPERTY_TOTAL_BYTES = 2 * 1024 * 1024;
    private static final int EVENT_PROPERTY_EXPLICIT_LOAD_BYTES = 2 * 1024 * 1024;

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ActorIdentityResolver actorIdentityResolver;

    public AdminEventJourneyService(MultiDataSourceManager dataSourceManager,
                                    ObjectMapper objectMapper,
                                    ActorIdentityResolver actorIdentityResolver) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.actorIdentityResolver = actorIdentityResolver;
    }

    /**
     * 以一条真实事件为锚点，返回同一归一 actor 在指定时间窗内的连续事件。
     * 没有 actor 的遗留事件退化为同设备时间线；结果始终包含锚点附近事件并按发生时间正序返回。
     */
    public AdminEventJourneyResponse getJourney(String projectId,
                                                 String anchorEventId,
                                                 Integer beforeMinutes,
                                                 Integer afterMinutes) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (anchorEventId == null || anchorEventId.isBlank() || anchorEventId.length() > 160) {
            throw new IllegalArgumentException("anchorEventId 格式无效");
        }
        int normalizedBefore = normalizeJourneyWindow(beforeMinutes);
        int normalizedAfter = normalizeJourneyWindow(afterMinutes);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        String actorLinksTable = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");

        List<RawAdminEventRecord> anchors = jdbcTemplate.query(
                String.format(
                        "SELECT %s FROM %s WHERE project_id = ? AND event_id = ? LIMIT 1",
                        journeyProjectedColumns(),
                        eventsTable
                ),
                (resultSet, rowNum) -> readRawJourneyEventRecord(resultSet),
                JOURNEY_PROPERTY_PER_EVENT_BYTES,
                normalizedProjectId,
                anchorEventId.trim()
        );
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("anchorEventId 不存在");
        }
        RawAdminEventRecord anchor = anchors.getFirst();
        long rangeStart = anchor.eventTimestamp() - Duration.ofMinutes(normalizedBefore).toMillis();
        long rangeEnd = anchor.eventTimestamp() + Duration.ofMinutes(normalizedAfter).toMillis();
        // 查询使用半开区间，但响应保留用户选择的真实结束时刻。
        long rangeEndExclusive = rangeEnd + 1;

        String subjectType;
        String resolvedActorId = null;
        String subjectWhere;
        List<Object> subjectArgs = new ArrayList<>();
        if (anchor.userId() != null
                && !anchor.userId().isBlank()
                && CryptoUtils.isValidUUID(anchor.userId().trim())) {
            List<String> actorMembers = actorIdentityResolver.resolveActorMembers(
                    jdbcTemplate, actorLinksTable, normalizedProjectId, anchor.userId().trim()
            );
            subjectType = "actor";
            resolvedActorId = actorIdentityResolver.resolveCanonicalActors(
                    jdbcTemplate, actorLinksTable, normalizedProjectId, List.of(anchor.userId())
            ).getOrDefault(anchor.userId(), anchor.userId());
            // 历史客户端可能以不同大小写写入合法 UUID，查询按规范化文本匹配。
            subjectWhere = "lower(user_id) IN ("
                    + String.join(",", actorMembers.stream().map(ignored -> "?").toList()) + ")";
            subjectArgs.addAll(actorMembers);
        } else if (anchor.deviceId() != null && !anchor.deviceId().isBlank()) {
            subjectType = "device";
            subjectWhere = "device_id = ?::uuid";
            subjectArgs.add(anchor.deviceId());
        } else {
            throw new IllegalArgumentException("该事件缺少可关联的统计身份");
        }

        List<Object> commonArgs = new ArrayList<>();
        commonArgs.add(normalizedProjectId);
        commonArgs.add(rangeStart);
        commonArgs.add(rangeEndExclusive);
        commonArgs.addAll(subjectArgs);
        String where = " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? AND " + subjectWhere;
        Long total = jdbcTemplate.queryForObject(
                String.format("SELECT COUNT(*) FROM %s %s", eventsTable, where),
                Long.class,
                commonArgs.toArray()
        );
        long totalValue = total == null ? 0L : total;

        List<RawAdminEventRecord> rawItems = queryNearestJourneyCandidates(
                jdbcTemplate, eventsTable, where, commonArgs, anchor
        ).stream().sorted(
                Comparator.comparingLong((RawAdminEventRecord item) ->
                                Math.abs(item.eventTimestamp() - anchor.eventTimestamp()))
                        .thenComparingInt(item -> item.eventId().equals(anchor.eventId()) ? 0 : 1)
                        .thenComparing(RawAdminEventRecord::eventTimestamp)
                        .thenComparingLong(RawAdminEventRecord::rowId)
        ).limit(JOURNEY_EVENT_LIMIT).toList();
        Map<String, Long> rowOrderByEvent = rawItems.stream().collect(Collectors.toUnmodifiableMap(
                RawAdminEventRecord::eventId,
                RawAdminEventRecord::rowId
        ));
        Map<String, Integer> propertyBytesByEvent = rawItems.stream().collect(Collectors.toUnmodifiableMap(
                RawAdminEventRecord::eventId,
                RawAdminEventRecord::propertiesBytes
        ));
        JourneyPropertyBudget propertyBudget = new JourneyPropertyBudget(JOURNEY_PROPERTY_TOTAL_BYTES);
        List<AdminJourneyEventRecord> items = resolveRecords(
                jdbcTemplate, actorLinksTable, normalizedProjectId, rawItems
        ).stream().map(event -> toJourneyRecord(
                event,
                propertyBudget,
                propertyBytesByEvent.getOrDefault(event.eventId(), 0)
        )).sorted(
                Comparator.comparing(AdminJourneyEventRecord::eventTimestamp)
                        .thenComparingLong(event -> rowOrderByEvent.getOrDefault(event.eventId(), Long.MAX_VALUE))
                        .thenComparing(AdminJourneyEventRecord::eventId)
        ).toList();

        return new AdminEventJourneyResponse(
                normalizedProjectId,
                anchor.eventId(),
                subjectType,
                resolvedActorId,
                Instant.ofEpochMilli(rangeStart).toString(),
                Instant.ofEpochMilli(rangeEnd).toString(),
                totalValue,
                totalValue > JOURNEY_EVENT_LIMIT,
                items
        );
    }

    public AdminEventPropertiesResponse getEventProperties(String projectId, String eventId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (eventId == null || eventId.isBlank() || eventId.length() > 160) {
            throw new IllegalArgumentException("eventId 格式无效");
        }
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        List<EventPropertyPayload> properties = jdbcTemplate.query(
                String.format(
                        "SELECT CASE WHEN properties_size_bytes IS NULL OR properties_size_bytes <= ? "
                                + "THEN properties::text ELSE NULL END AS properties, properties_size_bytes "
                                + "FROM %s WHERE project_id = ? AND event_id = ? LIMIT 1",
                        eventsTable
                ),
                (resultSet, rowNum) -> new EventPropertyPayload(
                        resultSet.getString("properties"),
                        (Integer) resultSet.getObject("properties_size_bytes")
                ),
                EVENT_PROPERTY_EXPLICIT_LOAD_BYTES,
                normalizedProjectId,
                eventId.trim()
        );
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("eventId 不存在");
        }
        EventPropertyPayload payload = properties.getFirst();
        if (payload.propertiesBytes() != null
                && payload.propertiesBytes() > EVENT_PROPERTY_EXPLICIT_LOAD_BYTES) {
            throw new IllegalArgumentException("事件属性超过在线查看上限");
        }
        return new AdminEventPropertiesResponse(
                normalizedProjectId,
                eventId.trim(),
                parseProperties(payload.properties())
        );
    }

    private List<RawAdminEventRecord> queryNearestJourneyCandidates(JdbcTemplate jdbcTemplate,
                                                                     String eventsTable,
                                                                     String where,
                                                                     List<Object> commonArgs,
                                                                     RawAdminEventRecord anchor) {
        String columns = "id, event_id, event_type, event_timestamp, created_at, device_id, user_id, "
                + "session_id, properties, properties_bytes, identity_scope";
        String projectedColumns = journeyProjectedColumns();
        String sql = String.format(
                "SELECT %s FROM (SELECT %s FROM %s %s AND event_timestamp <= ? "
                        + "ORDER BY event_timestamp DESC, CASE WHEN event_id = ? THEN 0 ELSE 1 END, id DESC LIMIT ?) before_anchor "
                        + "UNION ALL SELECT %s FROM (SELECT %s FROM %s %s AND event_timestamp > ? "
                        + "ORDER BY event_timestamp ASC, id ASC LIMIT ?) after_anchor",
                columns, projectedColumns, eventsTable, where,
                columns, projectedColumns, eventsTable, where
        );
        List<Object> args = new ArrayList<>();
        addJourneyProjectionArgs(args);
        args.addAll(commonArgs);
        args.add(anchor.eventTimestamp());
        args.add(anchor.eventId());
        args.add(JOURNEY_EVENT_LIMIT);
        addJourneyProjectionArgs(args);
        args.addAll(commonArgs);
        args.add(anchor.eventTimestamp());
        args.add(JOURNEY_EVENT_LIMIT);
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> readRawJourneyEventRecord(resultSet), args.toArray());
    }

    private String journeyProjectedColumns() {
        return "id, event_id, event_type, event_timestamp, created_at, device_id, user_id, session_id, "
                + "CASE WHEN properties_size_bytes IS NOT NULL AND properties_size_bytes <= ? "
                + "THEN properties ELSE NULL END AS properties, "
                + "COALESCE(properties_size_bytes, -1) AS properties_bytes, identity_scope";
    }

    private void addJourneyProjectionArgs(List<Object> args) {
        // 逻辑大小在事件写入时计算；候选查询不能为估算大小批量解压 JSONB。
        args.add(JOURNEY_PROPERTY_PER_EVENT_BYTES);
    }

    private int normalizeJourneyWindow(Integer minutes) {
        if (minutes == null) {
            return DEFAULT_JOURNEY_WINDOW_MINUTES;
        }
        if (minutes < 1 || minutes > MAX_JOURNEY_WINDOW_MINUTES) {
            throw new IllegalArgumentException("旅程时间窗必须在 1 分钟到 7 天之间");
        }
        return minutes;
    }

    private RawAdminEventRecord readRawJourneyEventRecord(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        int propertiesBytes = resultSet.getInt("properties_bytes");
        return new RawAdminEventRecord(
                resultSet.getString("event_id"),
                resultSet.getString("event_type"),
                resultSet.getLong("event_timestamp"),
                resultSet.getTimestamp("created_at").toInstant().toString(),
                resultSet.getString("device_id"),
                resultSet.getString("user_id"),
                resultSet.getString("session_id"),
                parseProperties(resultSet.getString("properties")),
                propertiesBytes,
                resultSet.getLong("id"),
                resultSet.getString("identity_scope")
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
            return new AdminEventRecord(
                    item.eventId(),
                    item.eventType(),
                    item.eventTimestamp(),
                    item.createdAt(),
                    item.deviceId(),
                    item.userId(),
                    resolvedActor,
                    item.identityScope(),
                    item.userId() != null
                            && !actorIdentityResolver.representsSameActor(item.userId(), resolvedActor),
                    item.sessionId(),
                    item.properties()
            );
        }).toList();
    }

    private AdminJourneyEventRecord toJourneyRecord(AdminEventRecord event,
                                                     JourneyPropertyBudget budget,
                                                     int propertiesBytes) {
        JsonNode properties = event.properties();
        boolean deferred = propertiesBytes < 0 || propertiesBytes > JOURNEY_PROPERTY_PER_EVENT_BYTES;
        if (deferred) {
            properties = null;
        } else if (properties != null && !budget.reserve(propertiesBytes)) {
            properties = null;
            deferred = true;
        }
        return new AdminJourneyEventRecord(
                event.eventId(), event.eventType(), event.eventTimestamp(), event.createdAt(),
                event.deviceId(), event.userId(), event.resolvedActorId(), event.identityScope(),
                event.actorLinked(), event.sessionId(), properties, propertiesBytes,
                propertiesBytes <= EVENT_PROPERTY_EXPLICIT_LOAD_BYTES, deferred
        );
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (normalizedProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        } catch (IllegalArgumentException exception) {
            logDebug("Invalid projectId", normalizedProjectId, exception);
            throw BusinessException.invalidProject(normalizedProjectId);
        } catch (Exception exception) {
            logDebug("Failed to load project config", normalizedProjectId, exception);
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
        } catch (Exception exception) {
            log.log(System.Logger.Level.WARNING, "Project datasource unavailable: {0}", normalizedProjectId);
            logDebug("Project datasource unavailable", normalizedProjectId, exception);
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }
    }

    private static String normalizeProjectId(String projectId) {
        if (projectId == null) {
            return "";
        }
        String stripped = projectId.strip();
        StringBuilder builder = new StringBuilder(stripped.length());
        for (int index = 0; index < stripped.length(); index++) {
            char character = stripped.charAt(index);
            if (Character.isWhitespace(character)
                    || Character.isSpaceChar(character)
                    || Character.getType(character) == Character.FORMAT) {
                continue;
            }
            builder.append(character);
        }
        return builder.toString();
    }

    private static void logDebug(String message, String projectId, Exception exception) {
        if (exception == null) {
            log.log(System.Logger.Level.DEBUG, "{0}: projectId={1}", message, debugValue(projectId));
        } else {
            log.log(System.Logger.Level.DEBUG, "{0}: projectId={1}, error={2}",
                    message, debugValue(projectId), exception.getMessage());
        }
    }

    private static String debugValue(String value) {
        if (value == null) {
            return "len=0 hex=<null>";
        }
        StringBuilder hex = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            if (index > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%04x", (int) value.charAt(index)));
        }
        return "len=" + value.length() + " hex=" + hex;
    }

    private record RawAdminEventRecord(
            String eventId,
            String eventType,
            Long eventTimestamp,
            String createdAt,
            String deviceId,
            String userId,
            String sessionId,
            JsonNode properties,
            int propertiesBytes,
            long rowId,
            String identityScope
    ) {}

    private record EventPropertyPayload(String properties, Integer propertiesBytes) {}

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private static final class JourneyPropertyBudget {
        private int remainingBytes;

        private JourneyPropertyBudget(int remainingBytes) {
            this.remainingBytes = remainingBytes;
        }

        private boolean reserve(int bytes) {
            if (bytes > remainingBytes) {
                return false;
            }
            remainingBytes -= bytes;
            return true;
        }
    }
}
