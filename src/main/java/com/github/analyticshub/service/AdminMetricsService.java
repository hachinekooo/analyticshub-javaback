package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminAppVersionDistributionItem;
import com.github.analyticshub.dto.AdminAppVersionDistributionResponse;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTopEvent;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.AdminMetricsTrendPoint;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理端运营数据服务
 */
@Service
public class AdminMetricsService {

    private static final System.Logger log = System.getLogger(AdminMetricsService.class.getName());
    private static final String APP_VERSION_MEASUREMENT = "latest_occurred_event_per_device";
    private static final String UNKNOWN_VERSION = "unknown";

    private final MultiDataSourceManager dataSourceManager;
    private final SemanticDictionaryService semanticDictionaryService;
    private final ActorIdentityResolver actorIdentityResolver;

    public AdminMetricsService(
            MultiDataSourceManager dataSourceManager,
            SemanticDictionaryService semanticDictionaryService,
            ActorIdentityResolver actorIdentityResolver
    ) {
        this.dataSourceManager = dataSourceManager;
        this.semanticDictionaryService = semanticDictionaryService;
        this.actorIdentityResolver = actorIdentityResolver;
    }

    public AdminMetricsOverviewResponse getOverview(String projectId, String from, String to) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String devicesTable = dataSourceManager.getTableName(normalizedProjectId, "devices");
        String sessionsTable = dataSourceManager.getTableName(normalizedProjectId, "sessions");
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        String actorLinksTable = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");

        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();

        long devicesTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ?",
                devicesTable, normalizedProjectId);
        // 活跃设备由区间内真实事件决定；注册/凭据轮换时间不能代替使用行为。
        long devicesActive = queryCount(jdbcTemplate,
                "SELECT COUNT(DISTINCT device_id) FROM %s "
                        + "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?",
                eventsTable, normalizedProjectId, eventStart, eventEnd);
        long sessionsTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?",
                sessionsTable, normalizedProjectId, start, end);
        long eventsTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?",
                eventsTable, normalizedProjectId, eventStart, eventEnd);
        List<String> activeActorIds = jdbcTemplate.query(
                String.format(
                        "SELECT DISTINCT user_id FROM %s "
                                + "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?",
                        eventsTable
                ),
                (resultSet, rowNumber) -> resultSet.getString(1),
                normalizedProjectId,
                eventStart,
                eventEnd
        );
        long usersActive = actorIdentityResolver.resolveCanonicalActors(
                        jdbcTemplate,
                        actorLinksTable,
                        normalizedProjectId,
                        activeActorIds
                )
                .values()
                .stream()
                .distinct()
                .count();

        double avgDuration = queryAvg(jdbcTemplate,
                "SELECT COALESCE(AVG(session_duration_ms), 0) FROM %s WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?",
                sessionsTable, normalizedProjectId, start, end);
        long avgSessionDurationMs = Math.round(avgDuration);
        double avgEventsPerSession = sessionsTotal == 0 ? 0 : ((double) eventsTotal / (double) sessionsTotal);

        return new AdminMetricsOverviewResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                devicesTotal,
                devicesActive,
                usersActive,
                sessionsTotal,
                eventsTotal,
                avgSessionDurationMs,
                avgEventsPerSession
        );
    }

    public AdminMetricsTrendResponse getTrends(String projectId, String from, String to, String granularity) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        Granularity bucket = Granularity.from(granularity);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String sessionsTable = dataSourceManager.getTableName(normalizedProjectId, "sessions");
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");

        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();

        Map<Instant, Long> eventBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? " +
                        "GROUP BY bucket ORDER BY bucket",
                eventsTable, bucket.value(), normalizedProjectId, eventStart, eventEnd);
        Map<Instant, Long> activeDeviceBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "COUNT(DISTINCT device_id) AS total FROM %s "
                        + "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
                        + "GROUP BY bucket ORDER BY bucket",
                eventsTable, bucket.value(), normalizedProjectId, eventStart, eventEnd);

        Map<Instant, Long> sessionBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, session_start_time, 'UTC') AS bucket, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ? " +
                        "GROUP BY bucket ORDER BY bucket",
                sessionsTable, bucket.value(), normalizedProjectId, start, end);

        List<AdminMetricsTrendPoint> points = new ArrayList<>();
        ZonedDateTime cursor = bucket.truncate(range.start());
        ZonedDateTime endCursor = range.end().atZone(ZoneOffset.UTC);
        while (cursor.isBefore(endCursor)) {
            Instant key = cursor.toInstant();
            long events = eventBuckets.getOrDefault(key, 0L);
            long activeDevices = activeDeviceBuckets.getOrDefault(key, 0L);
            long sessions = sessionBuckets.getOrDefault(key, 0L);
            points.add(new AdminMetricsTrendPoint(key.toString(), events, activeDevices, sessions));
            cursor = bucket.next(cursor);
        }

        return new AdminMetricsTrendResponse(
                normalizedProjectId,
                bucket.value(),
                range.start().toString(),
                range.end().toString(),
                points
        );
    }

    public AdminMetricsTopEventsResponse getTopEvents(
            String projectId,
            String from,
            String to,
            Integer limit,
            String aggregation
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();

        int topN = (limit == null || limit < 1) ? 10 : Math.min(limit, 50);
        String aggregationMode = normalizeTopEventsAggregation(aggregation);

        String sql = String.format(
                "SELECT event_type, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? " +
                        "GROUP BY event_type",
                eventsTable
        );

        List<AdminMetricsTopEvent> rawItems = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new AdminMetricsTopEvent(rs.getString("event_type"), rs.getLong("total")),
                normalizedProjectId, eventStart, eventEnd
        );
        List<AdminMetricsTopEvent> items;
        if ("semantic".equals(aggregationMode)) {
            Map<String, String> resolutions = semanticDictionaryService
                    .resolveActiveEventSemanticKeys(normalizedProjectId);
            Map<String, Long> totals = new HashMap<>();
            for (AdminMetricsTopEvent item : rawItems) {
                String key = resolutions.getOrDefault(item.eventType(), item.eventType());
                totals.merge(key, item.count(), Long::sum);
            }
            items = totals.entrySet().stream()
                    .map(entry -> new AdminMetricsTopEvent(entry.getKey(), entry.getValue()))
                    .sorted(java.util.Comparator
                            .comparingLong(AdminMetricsTopEvent::count).reversed()
                            .thenComparing(AdminMetricsTopEvent::eventType))
                    .limit(topN)
                    .toList();
        } else {
            items = rawItems.stream()
                    .sorted(java.util.Comparator
                            .comparingLong(AdminMetricsTopEvent::count).reversed()
                            .thenComparing(AdminMetricsTopEvent::eventType))
                    .limit(topN)
                    .toList();
        }

        return new AdminMetricsTopEventsResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                items
        );
    }

    /**
     * 返回活跃设备版本分布。一个账号可能同时使用多个版本，因此这里按设备而不是 actor 归组。
     * 每台设备只采用所选范围内最后发生的事件，升级前后的版本不会被重复计数。
     */
    public AdminAppVersionDistributionResponse getAppVersionDistribution(
            String projectId,
            String from,
            String to
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");

        String sql = """
                WITH latest AS (
                    SELECT device_id,
                           event_timestamp,
                           COALESCE(NULLIF(LEFT(BTRIM(properties ->> 'app_version'), 50), ''), 'unknown')
                               AS app_version,
                           COALESCE(NULLIF(LEFT(BTRIM(properties ->> 'build_number'), 50), ''), 'unknown')
                               AS build_number,
                           ROW_NUMBER() OVER (
                               PARTITION BY device_id
                               ORDER BY event_timestamp DESC, id DESC
                           ) AS row_number
                      FROM %s
                     WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?
                ), grouped AS (
                    SELECT app_version,
                           CASE WHEN app_version = 'unknown' THEN 'unknown' ELSE build_number END AS build_number,
                           COUNT(*) AS active_devices,
                           MAX(event_timestamp) AS last_observed_at
                      FROM latest
                     WHERE row_number = 1
                     GROUP BY app_version,
                              CASE WHEN app_version = 'unknown' THEN 'unknown' ELSE build_number END
                )
                SELECT app_version, build_number, active_devices, last_observed_at
                  FROM grouped
                 ORDER BY CASE WHEN app_version = 'unknown' THEN 1 ELSE 0 END,
                          active_devices DESC,
                          last_observed_at DESC,
                          app_version DESC,
                          build_number ASC
                """.formatted(eventsTable);

        List<VersionGroup> groups = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new VersionGroup(
                        resultSet.getString("app_version"),
                        resultSet.getString("build_number"),
                        resultSet.getLong("active_devices"),
                        resultSet.getLong("last_observed_at")
                ),
                normalizedProjectId,
                range.start().toEpochMilli(),
                range.end().toEpochMilli()
        );
        long activeDevices = groups.stream().mapToLong(VersionGroup::activeDevices).sum();
        long versionKnownDevices = groups.stream()
                .filter(group -> !UNKNOWN_VERSION.equals(group.appVersion()))
                .mapToLong(VersionGroup::activeDevices)
                .sum();
        List<AdminAppVersionDistributionItem> items = groups.stream()
                .map(group -> new AdminAppVersionDistributionItem(
                        group.appVersion(),
                        group.buildNumber(),
                        group.activeDevices(),
                        activeDevices == 0 ? 0d : roundRatio((double) group.activeDevices() / activeDevices),
                        Instant.ofEpochMilli(group.lastObservedAt()).toString()
                ))
                .toList();

        return new AdminAppVersionDistributionResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                APP_VERSION_MEASUREMENT,
                activeDevices,
                versionKnownDevices,
                activeDevices == 0 ? 0d : roundRatio((double) versionKnownDevices / activeDevices),
                items
        );
    }

    private static double roundRatio(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static String normalizeTopEventsAggregation(String aggregation) {
        String normalized = aggregation == null || aggregation.isBlank()
                ? "raw"
                : aggregation.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("raw", "semantic").contains(normalized)) {
            throw new IllegalArgumentException("aggregation 仅支持 raw/semantic");
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
            logDebug("Project datasource unavailable", normalizedProjectId, e);
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }
    }

    private long queryCount(JdbcTemplate jdbcTemplate, String template, String table, Object... args) {
        return queryCountSql(jdbcTemplate, String.format(template, table), args);
    }

    private long queryCountSql(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0L : result;
    }

    private double queryAvg(JdbcTemplate jdbcTemplate, String template, String table, Object... args) {
        String sql = String.format(template, table);
        Number result = jdbcTemplate.queryForObject(sql, Number.class, args);
        return result == null ? 0d : result.doubleValue();
    }

    private Map<Instant, Long> queryBucketCounts(JdbcTemplate jdbcTemplate, String template, String table,
                                                 Object... args) {
        String sql = String.format(template, table);
        Map<Instant, Long> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Instant bucket;
            try {
                OffsetDateTime odt = rs.getObject("bucket", OffsetDateTime.class);
                bucket = odt != null ? odt.toInstant() : null;
            } catch (Exception e) {
                Timestamp ts = rs.getTimestamp("bucket");
                bucket = ts != null ? ts.toInstant() : null;
            }
            long total = rs.getLong("total");
            if (bucket != null) {
                result.put(bucket, total);
            }
        }, args);
        return result;
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private record VersionGroup(
            String appVersion,
            String buildNumber,
            long activeDevices,
            long lastObservedAt
    ) {}

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
        System.Logger logger = System.getLogger(AdminMetricsService.class.getName());
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

    private enum Granularity {
        DAY("day", ChronoUnit.DAYS),
        HOUR("hour", ChronoUnit.HOURS);

        private final String value;
        private final ChronoUnit unit;

        Granularity(String value, ChronoUnit unit) {
            this.value = value;
            this.unit = unit;
        }

        public String value() {
            return value;
        }

        public static Granularity from(String value) {
            if (value == null || value.isBlank()) {
                return DAY;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "hour", "hours" -> HOUR;
                case "day", "days" -> DAY;
                default -> throw new IllegalArgumentException("granularity 仅支持 day/hour");
            };
        }

        public ZonedDateTime truncate(Instant instant) {
            ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
            return zdt.truncatedTo(unit);
        }

        public ZonedDateTime next(ZonedDateTime zdt) {
            return zdt.plus(1, unit);
        }
    }
}
