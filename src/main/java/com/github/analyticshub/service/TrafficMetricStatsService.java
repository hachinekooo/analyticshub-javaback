package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.dto.TrafficMetricTrendResponse;
import com.github.analyticshub.dto.TrafficMetricTrendPoint;
import com.github.analyticshub.dto.TrafficMetricTopResponse;
import com.github.analyticshub.dto.TrafficMetricTopItem;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;

@Service
public class TrafficMetricStatsService {

    private final MultiDataSourceManager dataSourceManager;

    public TrafficMetricStatsService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public TrafficMetricSummaryResponse getSummary(String projectId, String from, String to) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String table = dataSourceManager.getTableName(normalizedProjectId, "traffic_metrics");
        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());

        String sql = String.format(
                "SELECT " +
                        "SUM(CASE WHEN metric_type = 'page_view' THEN 1 ELSE 0 END) AS page_views, " +
                        "COUNT(DISTINCT CASE WHEN metric_type = 'page_view' THEN device_id::text ELSE NULL END) AS visitors " +
                        "FROM %s WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "AND (metadata->>'isBot' IS NULL OR metadata->>'isBot' != 'true')",
                table
        );

        return jdbcTemplate.query(sql, rs -> {
            long pageViews = 0L;
            long visitors = 0L;
            if (rs.next()) {
                pageViews = rs.getLong("page_views");
                visitors = rs.getLong("visitors");
            }
            return new TrafficMetricSummaryResponse(
                    normalizedProjectId,
                    range.start().toString(),
                    range.end().toString(),
                    pageViews,
                    visitors
            );
        }, normalizedProjectId, start, end);
    }

    public TrafficMetricTrendResponse getTrends(String projectId, String from, String to, String granularity) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        Granularity bucket = Granularity.from(granularity);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String table = dataSourceManager.getTableName(normalizedProjectId, "traffic_metrics");
        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());

        String sql = String.format(
                "SELECT date_trunc(?, created_at) AS bucket, " +
                        "SUM(CASE WHEN metric_type = 'page_view' THEN 1 ELSE 0 END) AS pv, " +
                        "COUNT(DISTINCT device_id) AS uv " +
                        "FROM %s WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "AND (metadata->>'isBot' IS NULL OR metadata->>'isBot' != 'true') " +
                        "GROUP BY bucket ORDER BY bucket",
                table
        );

        java.util.Map<java.time.Instant, TrafficMetricTrendPoint> dataMap = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            java.time.Instant time;
            try {
                java.time.OffsetDateTime odt = rs.getObject("bucket", java.time.OffsetDateTime.class);
                time = odt != null ? odt.toInstant() : null;
            } catch (Exception e) {
                java.sql.Timestamp ts = rs.getTimestamp("bucket");
                time = ts != null ? ts.toInstant() : null;
            }
            if (time != null) {
                dataMap.put(time, new TrafficMetricTrendPoint(time.toString(), rs.getLong("pv"), rs.getLong("uv")));
            }
        }, bucket.value(), normalizedProjectId, start, end);

        java.util.List<TrafficMetricTrendPoint> points = new java.util.ArrayList<>();
        java.time.ZonedDateTime cursor = bucket.truncate(range.start());
        java.time.ZonedDateTime endCursor = range.end().atZone(java.time.ZoneOffset.UTC);
        while (cursor.isBefore(endCursor)) {
            java.time.Instant key = cursor.toInstant();
            points.add(dataMap.getOrDefault(key, new TrafficMetricTrendPoint(key.toString(), 0L, 0L)));
            cursor = bucket.next(cursor);
        }

        return new TrafficMetricTrendResponse(normalizedProjectId, bucket.value(), range.start().toString(), range.end().toString(), points);
    }

    public TrafficMetricTopResponse getTopPages(String projectId, String from, String to, Integer limit) {
        return getTopMetrics(projectId, from, to, limit, "page_path");
    }

    public TrafficMetricTopResponse getTopReferrers(String projectId, String from, String to, Integer limit) {
        return getTopMetrics(projectId, from, to, limit, "referrer");
    }

    private TrafficMetricTopResponse getTopMetrics(String projectId, String from, String to, Integer limit, String column) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String table = dataSourceManager.getTableName(normalizedProjectId, "traffic_metrics");
        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());
        int topN = (limit == null || limit < 1) ? 10 : Math.min(limit, 100);

        String sql = String.format(
                "SELECT %s AS item_key, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "AND metric_type = 'page_view' AND %s IS NOT NULL " +
                        "AND (metadata->>'isBot' IS NULL OR metadata->>'isBot' != 'true') " +
                        "GROUP BY item_key ORDER BY total DESC LIMIT %d",
                column, table, column, topN
        );

        java.util.List<com.github.analyticshub.dto.TrafficMetricTopItem> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new com.github.analyticshub.dto.TrafficMetricTopItem(rs.getString("item_key"), rs.getLong("total")),
                normalizedProjectId, start, end
        );

        return new TrafficMetricTopResponse(normalizedProjectId, range.start().toString(), range.end().toString(), items);
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

    private enum Granularity {
        DAY("day", java.time.temporal.ChronoUnit.DAYS),
        HOUR("hour", java.time.temporal.ChronoUnit.HOURS);

        private final String value;
        private final java.time.temporal.ChronoUnit unit;

        Granularity(String value, java.time.temporal.ChronoUnit unit) {
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
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "hour", "hours" -> HOUR;
                case "day", "days" -> DAY;
                default -> DAY;
            };
        }

        public java.time.ZonedDateTime truncate(java.time.Instant instant) {
            java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneOffset.UTC);
            return zdt.truncatedTo(unit);
        }

        public java.time.ZonedDateTime next(java.time.ZonedDateTime zdt) {
            return zdt.plus(1, unit);
        }
    }
}
