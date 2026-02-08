package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端运营数据服务
 */
@Service
public class AdminMetricsService {

    private static final System.Logger log = System.getLogger(AdminMetricsService.class.getName());

    private final MultiDataSourceManager dataSourceManager;

    public AdminMetricsService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public AdminMetricsOverviewResponse getOverview(String projectId, String from, String to) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String devicesTable = dataSourceManager.getTableName(normalizedProjectId, "devices");
        String sessionsTable = dataSourceManager.getTableName(normalizedProjectId, "sessions");
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");

        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());

        long devicesTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ?",
                devicesTable, normalizedProjectId);
        long devicesActive = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ? AND last_active_at >= ? AND last_active_at < ?",
                devicesTable, normalizedProjectId, start, end);
        long sessionsTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?",
                sessionsTable, normalizedProjectId, start, end);
        long eventsTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ? AND created_at >= ? AND created_at < ?",
                eventsTable, normalizedProjectId, start, end);
        long usersActive = queryCount(jdbcTemplate,
                "SELECT COUNT(DISTINCT user_id) FROM %s WHERE project_id = ? AND created_at >= ? AND created_at < ?",
                eventsTable, normalizedProjectId, start, end);

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

        Map<Instant, Long> eventBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, created_at) AS bucket, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "GROUP BY bucket ORDER BY bucket",
                eventsTable, bucket.value(), normalizedProjectId, start, end);

        Map<Instant, Long> sessionBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, session_start_time) AS bucket, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ? " +
                        "GROUP BY bucket ORDER BY bucket",
                sessionsTable, bucket.value(), normalizedProjectId, start, end);

        List<AdminMetricsTrendPoint> points = new ArrayList<>();
        ZonedDateTime cursor = bucket.truncate(range.start());
        ZonedDateTime endCursor = range.end().atZone(ZoneOffset.UTC);
        while (cursor.isBefore(endCursor)) {
            Instant key = cursor.toInstant();
            long events = eventBuckets.getOrDefault(key, 0L);
            long sessions = sessionBuckets.getOrDefault(key, 0L);
            points.add(new AdminMetricsTrendPoint(key.toString(), events, sessions));
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

    public AdminMetricsTopEventsResponse getTopEvents(String projectId, String from, String to, Integer limit) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());

        int topN = (limit == null || limit < 1) ? 10 : Math.min(limit, 50);

        String sql = String.format(
                "SELECT event_type, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "GROUP BY event_type ORDER BY total DESC LIMIT %d",
                eventsTable,
                topN
        );

        List<AdminMetricsTopEvent> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new AdminMetricsTopEvent(rs.getString("event_type"), rs.getLong("total")),
                normalizedProjectId, start, end
        );

        return new AdminMetricsTopEventsResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                items
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
        String sql = String.format(template, table);
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
            Timestamp bucket = rs.getTimestamp("bucket");
            long total = rs.getLong("total");
            if (bucket != null) {
                result.put(bucket.toInstant(), total);
            }
        }, args);
        return result;
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
