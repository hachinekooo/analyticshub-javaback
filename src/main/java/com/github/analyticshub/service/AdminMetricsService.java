package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.dto.AdminAppVersionDistributionItem;
import com.github.analyticshub.dto.AdminAppVersionDistributionResponse;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTopEvent;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.AdminMetricsTrendPoint;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理端运营数据服务
 */
@Service
public class AdminMetricsService {

    private static final System.Logger log = System.getLogger(AdminMetricsService.class.getName());
    private static final String ACCOUNT_CREATED_SEMANTIC_KEY = OverviewMetricCatalog.ACCOUNT_CREATED;
    private static final String ACCOUNT_RECREATED_SEMANTIC_KEY = OverviewMetricCatalog.ACCOUNT_RECREATED;
    private static final String APP_VERSION_MEASUREMENT = "latest_occurred_event_per_device";
    private static final String UNKNOWN_VERSION = "unknown";

    private final MultiDataSourceManager dataSourceManager;
    private final SemanticDictionaryService semanticDictionaryService;
    private final ActorIdentityResolver actorIdentityResolver;
    private final AnalyticsPropertyFilterService propertyFilterService;
    private final AnalyticsQueryProperties queryProperties;
    private final ProjectTransactionExecutor projectTransactions;

    public AdminMetricsService(
            MultiDataSourceManager dataSourceManager,
            SemanticDictionaryService semanticDictionaryService,
            ActorIdentityResolver actorIdentityResolver,
            AnalyticsPropertyFilterService propertyFilterService,
            AnalyticsQueryProperties queryProperties,
            ProjectTransactionExecutor projectTransactions
    ) {
        this.dataSourceManager = dataSourceManager;
        this.semanticDictionaryService = semanticDictionaryService;
        this.actorIdentityResolver = actorIdentityResolver;
        this.propertyFilterService = propertyFilterService;
        this.queryProperties = queryProperties;
        this.projectTransactions = projectTransactions;
    }

    public AdminMetricsOverviewResponse getOverview(String projectId, String from, String to) {
        return getOverview(projectId, from, to, null);
    }

    public AdminMetricsOverviewResponse getOverview(
            String projectId,
            String from,
            String to,
            String propertyFilters
    ) {
        return executeInteractive(projectId, from, to,
                () -> getOverviewQuery(projectId, from, to, propertyFilters));
    }

    private AdminMetricsOverviewResponse getOverviewQuery(
            String projectId,
            String from,
            String to,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String devicesTable = dataSourceManager.getTableName(normalizedProjectId, "devices");
        String sessionsTable = dataSourceManager.getTableName(normalizedProjectId, "sessions");
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        String actorLinksTable = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");
        AnalyticsPropertyFilterService.CompiledPropertyFilters filters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");

        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();
        enforceEventScanBudget(
                jdbcTemplate, eventsTable, normalizedProjectId, eventStart, eventEnd, filters
        );
        if (filters.isEmpty()) {
            enforceSessionScanBudget(jdbcTemplate, sessionsTable, normalizedProjectId, start, end);
        }

        // 项目设备库存不属于事件属性分群；字段名明确其不参与 propertyFilters。
        long devicesInventoryTotal = queryCount(jdbcTemplate,
                "SELECT COUNT(*) FROM %s WHERE project_id = ?",
                devicesTable, normalizedProjectId);
        List<Object> eventArguments = eventArguments(normalizedProjectId, eventStart, eventEnd, filters);
        String eventFilterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        // 活跃设备由区间内真实事件决定；注册/凭据轮换时间不能代替使用行为。
        long devicesActive = queryCountSql(jdbcTemplate,
                "SELECT COUNT(DISTINCT device_id) FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + eventFilterSql,
                eventArguments.toArray());
        long sessionsTotal = filters.isEmpty()
                ? queryCount(jdbcTemplate,
                        "SELECT COUNT(*) FROM %s WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?",
                        sessionsTable, normalizedProjectId, start, end)
                : queryCountSql(jdbcTemplate,
                        "SELECT COUNT(DISTINCT session_id) FROM " + eventsTable
                                + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                                + " AND session_id IS NOT NULL" + eventFilterSql,
                        eventArguments.toArray());
        long eventsTotal = queryCountSql(jdbcTemplate,
                "SELECT COUNT(*) FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + eventFilterSql,
                eventArguments.toArray());
        List<Object> activeActorArguments = new ArrayList<>(eventArguments);
        activeActorArguments.add(queryProperties.getMaxCandidateRows() + 1);
        List<String> activeActorIds = jdbcTemplate.query(
                "SELECT DISTINCT user_id FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + eventFilterSql + " LIMIT ?",
                (resultSet, rowNumber) -> resultSet.getString(1),
                activeActorArguments.toArray()
        );
        enforceCandidateBudget(activeActorIds.size());
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
        Map<String, List<String>> accountAliases = semanticDictionaryService.resolveAvailableActiveEventAliases(
                normalizedProjectId,
                List.of(ACCOUNT_CREATED_SEMANTIC_KEY, ACCOUNT_RECREATED_SEMANTIC_KEY)
        );
        long cloudAccountsCreated = queryEventCount(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                eventStart,
                eventEnd,
                accountAliases.getOrDefault(ACCOUNT_CREATED_SEMANTIC_KEY, List.of()),
                filters
        );
        long cloudAccountsRecreated = queryEventCount(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                eventStart,
                eventEnd,
                accountAliases.getOrDefault(ACCOUNT_RECREATED_SEMANTIC_KEY, List.of()),
                filters
        );

        double avgDuration;
        if (filters.isEmpty()) {
            avgDuration = queryAvg(jdbcTemplate,
                    "SELECT COALESCE(AVG(session_duration_ms), 0) FROM %s WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?",
                    sessionsTable, normalizedProjectId, start, end);
        } else {
            List<Object> avgArguments = new ArrayList<>(eventArguments);
            avgArguments.add(normalizedProjectId);
            avgArguments.add(start);
            avgArguments.add(end);
            Number result = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(AVG(s.session_duration_ms), 0) FROM " + sessionsTable + " s "
                            + "JOIN (SELECT DISTINCT session_id FROM " + eventsTable + " e "
                            + "WHERE e.project_id = ? AND e.event_timestamp >= ? AND e.event_timestamp < ? "
                            + "AND e.session_id IS NOT NULL"
                            + eventFilterSql.replace("properties", "e.properties") + ") matched "
                            + "ON matched.session_id = s.session_id "
                            + "WHERE s.project_id = ? AND s.session_start_time >= ? AND s.session_start_time < ?",
                    Number.class,
                    avgArguments.toArray()
            );
            avgDuration = result == null ? 0d : result.doubleValue();
        }
        long avgSessionDurationMs = Math.round(avgDuration);
        double avgEventsPerSession = sessionsTotal == 0 ? 0 : ((double) eventsTotal / (double) sessionsTotal);

        return new AdminMetricsOverviewResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                devicesInventoryTotal,
                devicesActive,
                usersActive,
                cloudAccountsCreated,
                cloudAccountsRecreated,
                sessionsTotal,
                eventsTotal,
                avgSessionDurationMs,
                avgEventsPerSession,
                OverviewMetricCatalog.availableOverviewKeys(accountAliases)
        );
    }

    public AdminMetricsTrendResponse getTrends(String projectId, String from, String to, String granularity) {
        return getTrends(projectId, from, to, granularity, null);
    }

    public AdminMetricsTrendResponse getTrends(
            String projectId,
            String from,
            String to,
            String granularity,
            String propertyFilters
    ) {
        return executeInteractive(projectId, from, to,
                () -> getTrendsQuery(projectId, from, to, granularity, propertyFilters));
    }

    private AdminMetricsTrendResponse getTrendsQuery(
            String projectId,
            String from,
            String to,
            String granularity,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        Granularity bucket = Granularity.from(granularity);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String sessionsTable = dataSourceManager.getTableName(normalizedProjectId, "sessions");
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        String actorLinksTable = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");
        AnalyticsPropertyFilterService.CompiledPropertyFilters filters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");

        Timestamp start = Timestamp.from(range.start());
        Timestamp end = Timestamp.from(range.end());
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();
        enforceEventScanBudget(
                jdbcTemplate, eventsTable, normalizedProjectId, eventStart, eventEnd, filters
        );
        if (filters.isEmpty()) {
            enforceSessionScanBudget(jdbcTemplate, sessionsTable, normalizedProjectId, start, end);
        }
        String eventFilterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        List<Object> bucketArguments = new ArrayList<>();
        bucketArguments.add(bucket.value());
        bucketArguments.add(normalizedProjectId);
        bucketArguments.add(eventStart);
        bucketArguments.add(eventEnd);
        bucketArguments.addAll(filters.arguments());

        Map<Instant, Long> eventBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
                        + eventFilterSql + " " +
                        "GROUP BY bucket ORDER BY bucket",
                eventsTable, bucketArguments.toArray());
        Map<Instant, Long> activeDeviceBuckets = queryBucketCounts(jdbcTemplate,
                "SELECT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "COUNT(DISTINCT device_id) AS total FROM %s "
                        + "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
                        + eventFilterSql + " "
                        + "GROUP BY bucket ORDER BY bucket",
                eventsTable, bucketArguments.toArray());

        Map<Instant, Long> activeUserBuckets = queryActiveUserBuckets(
                jdbcTemplate,
                eventsTable,
                actorLinksTable,
                normalizedProjectId,
                eventStart,
                eventEnd,
                bucket,
                filters
        );
        Map<String, List<String>> accountAliases = semanticDictionaryService.resolveAvailableActiveEventAliases(
                normalizedProjectId,
                List.of(ACCOUNT_CREATED_SEMANTIC_KEY, ACCOUNT_RECREATED_SEMANTIC_KEY)
        );
        Map<Instant, Long> accountCreatedBuckets = queryEventBucketCounts(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                eventStart,
                eventEnd,
                bucket,
                accountAliases.getOrDefault(ACCOUNT_CREATED_SEMANTIC_KEY, List.of()),
                filters
        );
        Map<Instant, Long> accountRecreatedBuckets = queryEventBucketCounts(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                eventStart,
                eventEnd,
                bucket,
                accountAliases.getOrDefault(ACCOUNT_RECREATED_SEMANTIC_KEY, List.of()),
                filters
        );

        Map<Instant, Long> sessionBuckets;
        if (filters.isEmpty()) {
            sessionBuckets = queryBucketCounts(jdbcTemplate,
                    "SELECT date_trunc(?, session_start_time, 'UTC') AS bucket, COUNT(*) AS total FROM %s " +
                            "WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ? " +
                            "GROUP BY bucket ORDER BY bucket",
                    sessionsTable, bucket.value(), normalizedProjectId, start, end);
        } else {
            List<Object> sessionArguments = new ArrayList<>();
            sessionArguments.add(bucket.value());
            sessionArguments.addAll(eventArguments(normalizedProjectId, eventStart, eventEnd, filters));
            sessionArguments.add(normalizedProjectId);
            sessionArguments.add(start);
            sessionArguments.add(end);
            sessionBuckets = queryBucketCounts(jdbcTemplate,
                    "SELECT date_trunc(?, s.session_start_time, 'UTC') AS bucket, COUNT(*) AS total FROM %s s "
                            + "JOIN (SELECT DISTINCT session_id FROM " + eventsTable + " e "
                            + "WHERE e.project_id = ? AND e.event_timestamp >= ? AND e.event_timestamp < ? "
                            + "AND e.session_id IS NOT NULL"
                            + eventFilterSql.replace("properties", "e.properties") + ") matched "
                            + "ON matched.session_id = s.session_id "
                            + "WHERE s.project_id = ? AND s.session_start_time >= ? AND s.session_start_time < ? "
                            + "GROUP BY bucket ORDER BY bucket",
                    sessionsTable,
                    sessionArguments.toArray());
        }

        List<AdminMetricsTrendPoint> points = new ArrayList<>();
        ZonedDateTime cursor = bucket.truncate(range.start());
        ZonedDateTime endCursor = range.end().atZone(ZoneOffset.UTC);
        while (cursor.isBefore(endCursor)) {
            Instant key = cursor.toInstant();
            long events = eventBuckets.getOrDefault(key, 0L);
            long activeDevices = activeDeviceBuckets.getOrDefault(key, 0L);
            long activeUsers = activeUserBuckets.getOrDefault(key, 0L);
            long cloudAccountsCreated = accountCreatedBuckets.getOrDefault(key, 0L);
            long cloudAccountsRecreated = accountRecreatedBuckets.getOrDefault(key, 0L);
            long sessions = sessionBuckets.getOrDefault(key, 0L);
            points.add(new AdminMetricsTrendPoint(
                    key.toString(),
                    events,
                    activeDevices,
                    activeUsers,
                    cloudAccountsCreated,
                    cloudAccountsRecreated,
                    sessions
            ));
            cursor = bucket.next(cursor);
        }

        return new AdminMetricsTrendResponse(
                normalizedProjectId,
                bucket.value(),
                range.start().toString(),
                range.end().toString(),
                points,
                OverviewMetricCatalog.availableTrendKeys(accountAliases)
        );
    }

    public AdminMetricsTopEventsResponse getTopEvents(
            String projectId,
            String from,
            String to,
            Integer limit,
            String aggregation
    ) {
        return getTopEvents(projectId, from, to, limit, aggregation, null);
    }

    public AdminMetricsTopEventsResponse getTopEvents(
            String projectId,
            String from,
            String to,
            Integer limit,
            String aggregation,
            String propertyFilters
    ) {
        return executeInteractive(projectId, from, to,
                () -> getTopEventsQuery(projectId, from, to, limit, aggregation, propertyFilters));
    }

    private AdminMetricsTopEventsResponse getTopEventsQuery(
            String projectId,
            String from,
            String to,
            Integer limit,
            String aggregation,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        AnalyticsPropertyFilterService.CompiledPropertyFilters filters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");
        long eventStart = range.start().toEpochMilli();
        long eventEnd = range.end().toEpochMilli();
        enforceEventScanBudget(
                jdbcTemplate, eventsTable, normalizedProjectId, eventStart, eventEnd, filters
        );

        int topN = (limit == null || limit < 1) ? 10 : Math.min(limit, 50);
        String aggregationMode = normalizeTopEventsAggregation(aggregation);

        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        String sql = String.format(
                "SELECT event_type, COUNT(*) AS total FROM %s " +
                        "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
                        + filterSql + " " +
                        "GROUP BY event_type",
                eventsTable
        );
        List<Object> arguments = eventArguments(normalizedProjectId, eventStart, eventEnd, filters);

        List<AdminMetricsTopEvent> rawItems = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new AdminMetricsTopEvent(rs.getString("event_type"), rs.getLong("total")),
                arguments.toArray()
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
        return getAppVersionDistribution(projectId, from, to, null);
    }

    public AdminAppVersionDistributionResponse getAppVersionDistribution(
            String projectId,
            String from,
            String to,
            String propertyFilters
    ) {
        return executeInteractive(projectId, from, to,
                () -> getAppVersionDistributionQuery(projectId, from, to, propertyFilters));
    }

    private AdminAppVersionDistributionResponse getAppVersionDistributionQuery(
            String projectId,
            String from,
            String to,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        AnalyticsPropertyFilterService.CompiledPropertyFilters filters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");
        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        enforceEventScanBudget(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                range.start().toEpochMilli(),
                range.end().toEpochMilli(),
                filters
        );

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
                     WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?%s
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
                """.formatted(eventsTable, filterSql);

        List<Object> arguments = eventArguments(
                normalizedProjectId,
                range.start().toEpochMilli(),
                range.end().toEpochMilli(),
                filters
        );

        List<VersionGroup> groups = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new VersionGroup(
                        resultSet.getString("app_version"),
                        resultSet.getString("build_number"),
                        resultSet.getLong("active_devices"),
                        resultSet.getLong("last_observed_at")
                ),
                arguments.toArray()
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

    /**
     * 活跃用户按时间桶归组后再解析 canonical actor，避免登录前后身份在同一天重复计数。
     * SQL 只取每个桶内的 distinct actor，降低 Java 侧归并的数据量。
     */
    private Map<Instant, Long> queryActiveUserBuckets(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String actorLinksTable,
            String projectId,
            long eventStart,
            long eventEnd,
            Granularity granularity,
            AnalyticsPropertyFilterService.CompiledPropertyFilters filters
    ) {
        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        List<Object> arguments = new ArrayList<>();
        arguments.add(granularity.value());
        arguments.add(projectId);
        arguments.add(eventStart);
        arguments.add(eventEnd);
        arguments.addAll(filters.arguments());
        arguments.add(queryProperties.getMaxCandidateRows() + 1);
        List<BucketActor> rows = jdbcTemplate.query(
                "SELECT DISTINCT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "user_id FROM " + eventsTable + " "
                        + "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? "
                        + "AND user_id IS NOT NULL AND BTRIM(user_id) <> ''" + filterSql + " LIMIT ?",
                (resultSet, rowNumber) -> new BucketActor(
                        readBucket(resultSet),
                        resultSet.getString("user_id")
                ),
                arguments.toArray()
        );
        enforceCandidateBudget(rows.size());
        List<String> rawActors = rows.stream().map(BucketActor::rawActorId).distinct().toList();
        Map<String, String> canonicalActors = actorIdentityResolver.resolveCanonicalActors(
                jdbcTemplate,
                actorLinksTable,
                projectId,
                rawActors
        );
        Map<Instant, Set<String>> actorsByBucket = new HashMap<>();
        for (BucketActor row : rows) {
            if (row.bucket() == null) continue;
            String canonical = canonicalActors.getOrDefault(row.rawActorId(), row.rawActorId());
            actorsByBucket.computeIfAbsent(row.bucket(), ignored -> new HashSet<>()).add(canonical);
        }
        Map<Instant, Long> result = new HashMap<>();
        actorsByBucket.forEach((time, actors) -> result.put(time, (long) actors.size()));
        return result;
    }

    private long queryEventCount(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String projectId,
            long eventStart,
            long eventEnd,
            List<String> eventTypes,
            AnalyticsPropertyFilterService.CompiledPropertyFilters filters
    ) {
        if (eventTypes.isEmpty()) return 0L;
        String placeholders = String.join(",", java.util.Collections.nCopies(eventTypes.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        arguments.add(eventStart);
        arguments.add(eventEnd);
        arguments.addAll(eventTypes);
        arguments.addAll(filters.arguments());
        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        return queryCountSql(
                jdbcTemplate,
                "SELECT COUNT(*) FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + " AND event_type IN (" + placeholders + ")" + filterSql,
                arguments.toArray()
        );
    }

    private Map<Instant, Long> queryEventBucketCounts(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String projectId,
            long eventStart,
            long eventEnd,
            Granularity granularity,
            List<String> eventTypes,
            AnalyticsPropertyFilterService.CompiledPropertyFilters filters
    ) {
        if (eventTypes.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(eventTypes.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(granularity.value());
        arguments.add(projectId);
        arguments.add(eventStart);
        arguments.add(eventEnd);
        arguments.addAll(eventTypes);
        arguments.addAll(filters.arguments());
        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        return queryBucketCounts(
                jdbcTemplate,
                "SELECT date_trunc(?, to_timestamp(event_timestamp / 1000.0), 'UTC') AS bucket, "
                        + "COUNT(*) AS total FROM %s WHERE project_id = ? "
                        + "AND event_timestamp >= ? AND event_timestamp < ? "
                        + "AND event_type IN (" + placeholders + ")" + filterSql
                        + " GROUP BY bucket ORDER BY bucket",
                eventsTable,
                arguments.toArray()
        );
    }

    private static Instant readBucket(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        try {
            OffsetDateTime value = resultSet.getObject("bucket", OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        } catch (Exception ignored) {
            Timestamp value = resultSet.getTimestamp("bucket");
            return value == null ? null : value.toInstant();
        }
    }

    private static List<Object> eventArguments(
            String projectId,
            long eventStart,
            long eventEnd,
            AnalyticsPropertyFilterService.CompiledPropertyFilters filters
    ) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        arguments.add(eventStart);
        arguments.add(eventEnd);
        arguments.addAll(filters.arguments());
        return arguments;
    }

    /**
     * 聚合查询不能通过 LIMIT 主结果来控制成本，否则会把部分结果伪装成完整统计。
     * 这里先在同一只读事务内最多探测 max + 1 条候选事件，超限即明确失败。
     */
    private void enforceEventScanBudget(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String projectId,
            long eventStart,
            long eventEnd,
            AnalyticsPropertyFilterService.CompiledPropertyFilters filters
    ) {
        String filterSql = filters.isEmpty() ? "" : " AND " + filters.sql();
        List<Object> arguments = eventArguments(projectId, eventStart, eventEnd, filters);
        arguments.add(queryProperties.getMaxCandidateRows() + 1);
        Long candidateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + eventsTable
                        + " WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ?"
                        + filterSql + " LIMIT ?) bounded_candidates",
                Long.class,
                arguments.toArray()
        );
        enforceCandidateBudget(candidateCount == null ? 0L : candidateCount);
    }

    private void enforceSessionScanBudget(
            JdbcTemplate jdbcTemplate,
            String sessionsTable,
            String projectId,
            Timestamp start,
            Timestamp end
    ) {
        Long candidateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + sessionsTable
                        + " WHERE project_id = ? AND session_start_time >= ? AND session_start_time < ?"
                        + " LIMIT ?) bounded_candidates",
                Long.class,
                projectId,
                start,
                end,
                queryProperties.getMaxCandidateRows() + 1
        );
        enforceCandidateBudget(candidateCount == null ? 0L : candidateCount);
    }

    private <T> T executeInteractive(
            String projectId,
            String from,
            String to,
            java.util.function.Supplier<T> operation
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        if (Duration.between(range.start(), range.end())
                .compareTo(Duration.ofDays(queryProperties.getMaxRangeDays())) > 0) {
            throw BusinessException.analyticsQueryRangeExceeded(queryProperties.getMaxRangeDays());
        }
        ProjectContext context = requireProject(normalizedProjectId);
        try {
            // 内层 JdbcTemplate 会复用此处绑定到项目数据源的只读事务与 statement_timeout。
            return projectTransactions.executeReadOnly(
                    context.dataSource(),
                    queryProperties.getTimeoutSeconds(),
                    ignored -> operation.get()
            );
        } catch (QueryTimeoutException | TransactionTimedOutException exception) {
            throw BusinessException.analyticsQueryTimedOut();
        } catch (DataAccessException exception) {
            if (hasSqlState(exception, "57014")) {
                throw BusinessException.analyticsQueryTimedOut();
            }
            throw exception;
        }
    }

    private void enforceCandidateBudget(long candidateCount) {
        if (candidateCount > queryProperties.getMaxCandidateRows()) {
            throw BusinessException.analyticsQueryBudgetExceeded(queryProperties.getMaxCandidateRows());
        }
    }

    private static boolean hasSqlState(Throwable error, String expectedState) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && expectedState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private record VersionGroup(
            String appVersion,
            String buildNumber,
            long activeDevices,
            long lastObservedAt
    ) {}

    private record BucketActor(Instant bucket, String rawActorId) {}

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
