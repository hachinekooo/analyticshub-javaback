package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminFunnelGroupResult;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminFunnelStepResult;
import com.github.analyticshub.dto.AdminRetentionBucket;
import com.github.analyticshub.dto.AdminRetentionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端产品分析服务。
 *
 * <p>这里直接基于事件表计算漏斗和留存。未上线阶段优先保证口径清晰；
 * 数据量变大后可以把同一口径迁移到物化表或分析型存储。</p>
 */
@Service
public class AdminProductAnalyticsService {

    private static final System.Logger log = System.getLogger(AdminProductAnalyticsService.class.getName());
    private static final int MAX_FUNNEL_STEPS = 12;
    private static final int MAX_RETENTION_DAY = 90;
    private static final String ACTOR_FUNNEL_ATTRIBUTION_MODEL = "first_touch_actor";
    private static final String JOURNEY_FUNNEL_ATTRIBUTION_MODEL = "first_touch_journey";

    private final MultiDataSourceManager dataSourceManager;
    private final SemanticDictionaryService semanticDictionaryService;
    private final ActorIdentityResolver actorIdentityResolver;
    private final AnalyticsQueryProperties queryProperties;
    private final ProjectTransactionExecutor projectTransactions;
    private final AnalyticsPropertyFilterService propertyFilterService;

    public AdminProductAnalyticsService(
            MultiDataSourceManager dataSourceManager,
            SemanticDictionaryService semanticDictionaryService,
            ActorIdentityResolver actorIdentityResolver,
            AnalyticsQueryProperties queryProperties,
            ProjectTransactionExecutor projectTransactions,
            AnalyticsPropertyFilterService propertyFilterService
    ) {
        this.dataSourceManager = dataSourceManager;
        this.semanticDictionaryService = semanticDictionaryService;
        this.actorIdentityResolver = actorIdentityResolver;
        this.queryProperties = queryProperties;
        this.projectTransactions = projectTransactions;
        this.propertyFilterService = propertyFilterService;
    }

    public AdminFunnelResponse getFunnel(
            String projectId,
            String from,
            String to,
            String steps,
            String groupBy
    ) {
        return getFunnel(projectId, from, to, steps, groupBy, null, null);
    }

    public AdminFunnelResponse getFunnel(
            String projectId,
            String from,
            String to,
            String steps,
            String groupBy,
            String journeyKey
    ) {
        return getFunnel(projectId, from, to, steps, groupBy, journeyKey, null);
    }

    public AdminFunnelResponse getFunnel(
            String projectId,
            String from,
            String to,
            String steps,
            String groupBy,
            String journeyKey,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        validateInteractiveRange(range.start(), range.end());
        List<String> semanticSteps = parseEventList(steps, MAX_FUNNEL_STEPS, "steps");
        if (semanticSteps.size() < 2) {
            throw new IllegalArgumentException("steps 至少需要 2 个不同事件");
        }
        SemanticSelection selection = resolveSelection(normalizedProjectId, semanticSteps);
        String normalizedGroupBy = normalizePropertyKey(groupBy);
        String normalizedJourneyKey = normalizePropertyKey(journeyKey);
        AnalyticsPropertyDataType resolvedGroupDataType = propertyFilterService
                .requireGroupable(normalizedProjectId, normalizedGroupBy);
        AnalyticsPropertyDataType groupDataType = resolvedGroupDataType;
        propertyFilterService.requireJourneyKey(normalizedProjectId, normalizedJourneyKey);
        AnalyticsPropertyFilterService.CompiledPropertyFilters compiledFilters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");

        DataSource dataSource = requireProject(normalizedProjectId).dataSource();
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        List<EventRow> rows = executeInteractiveQuery(dataSource, jdbcTemplate -> {
            List<EventRow> queriedRows = queryEvents(
                    jdbcTemplate,
                    eventsTable,
                    normalizedProjectId,
                    range.start(),
                    range.end(),
                    selection.rawKeys(),
                    normalizedGroupBy,
                    groupDataType,
                    normalizedJourneyKey,
                    compiledFilters
            );
            queriedRows = canonicalize(queriedRows, selection.rawToSemantic());
            return resolveCanonicalActors(jdbcTemplate, normalizedProjectId, queriedRows);
        });

        Map<String, Map<String, ActorTimeline>> groups = buildFunnelGroups(
                rows,
                semanticSteps,
                normalizedGroupBy,
                groupDataType,
                normalizedJourneyKey
        );
        List<AdminFunnelGroupResult> groupResults = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AdminFunnelGroupResult(
                        entry.getKey(),
                        calculateFunnelSteps(semanticSteps, entry.getValue())
                ))
                .toList();

        return new AdminFunnelResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                semanticSteps,
                normalizedGroupBy,
                normalizedJourneyKey,
                normalizedJourneyKey.isBlank() ? "actors" : "journeys",
                normalizedJourneyKey.isBlank()
                        ? ACTOR_FUNNEL_ATTRIBUTION_MODEL
                        : JOURNEY_FUNNEL_ATTRIBUTION_MODEL,
                groupResults
        );
    }

    public AdminRetentionResponse getRetention(
            String projectId,
            String from,
            String to,
            String cohortEvent,
            String returnEvent,
            String days
    ) {
        return getRetention(projectId, from, to, cohortEvent, returnEvent, days, null);
    }

    public AdminRetentionResponse getRetention(
            String projectId,
            String from,
            String to,
            String cohortEvent,
            String returnEvent,
            String days,
            String propertyFilters
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        validateInteractiveRange(range.start(), range.end());
        String normalizedCohortEvent = requireEventName(cohortEvent, "cohortEvent");
        String normalizedReturnEvent = requireEventName(returnEvent, "returnEvent");
        SemanticSelection selection = resolveSelection(
                normalizedProjectId,
                List.of(normalizedCohortEvent, normalizedReturnEvent)
        );
        List<Integer> retentionDays = parseDays(days);
        int maxDay = retentionDays.stream().max(Integer::compareTo).orElse(30);
        Instant requestedObservationEnd = range.end().plus(Duration.ofDays(maxDay + 1L));
        validateInteractiveRange(range.start(), requestedObservationEnd);
        Instant observationEnd = earlier(requestedObservationEnd, Instant.now());
        AnalyticsPropertyFilterService.CompiledPropertyFilters compiledFilters =
                propertyFilterService.compile(normalizedProjectId, propertyFilters, "properties");

        DataSource dataSource = requireProject(normalizedProjectId).dataSource();
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        List<EventRow> rows = executeInteractiveQuery(dataSource, jdbcTemplate -> {
            List<EventRow> queriedRows = queryEvents(
                    jdbcTemplate,
                    eventsTable,
                    normalizedProjectId,
                    range.start(),
                    observationEnd,
                    selection.rawKeys(),
                    "",
                    null,
                    "",
                    compiledFilters
            );
            queriedRows = canonicalize(queriedRows, selection.rawToSemantic());
            return resolveCanonicalActors(jdbcTemplate, normalizedProjectId, queriedRows);
        });

        Map<String, EventPosition> cohortPositions = new HashMap<>();
        Map<String, List<EventPosition>> returnPositions = new HashMap<>();
        for (EventRow row : rows) {
            if (row.actorId().isBlank()) {
                continue;
            }
            if (normalizedCohortEvent.equals(row.eventType())
                    && !row.createdAt().isBefore(range.start())
                    && row.createdAt().isBefore(range.end())) {
                cohortPositions.merge(row.actorId(), row.position(), AdminProductAnalyticsService::earlier);
            }
            if (normalizedReturnEvent.equals(row.eventType())) {
                returnPositions.computeIfAbsent(row.actorId(), ignored -> new ArrayList<>()).add(row.position());
            }
        }
        returnPositions.values().forEach(positions -> positions.sort(Comparator.naturalOrder()));

        long cohortUsers = cohortPositions.size();
        List<AdminRetentionBucket> buckets = retentionDays.stream()
                .map(day -> {
                    RetentionCounts counts = calculateRetentionCounts(
                            cohortPositions, returnPositions, day, observationEnd
                    );
                    double rate = counts.eligibleUsers() == 0
                            ? 0d
                            : (double) counts.retainedUsers() / (double) counts.eligibleUsers();
                    return new AdminRetentionBucket(
                            day, counts.eligibleUsers(), counts.retainedUsers(), roundRate(rate)
                    );
                })
                .toList();

        return new AdminRetentionResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                observationEnd.toString(),
                requestedObservationEnd.toString(),
                !observationEnd.isBefore(requestedObservationEnd),
                normalizedCohortEvent,
                normalizedReturnEvent,
                cohortUsers,
                buckets
        );
    }

    private Map<String, Map<String, ActorTimeline>> buildFunnelGroups(
            List<EventRow> rows,
            List<String> stepEvents,
            String groupBy,
            AnalyticsPropertyDataType groupDataType,
            String journeyKey
    ) {
        String firstStep = stepEvents.get(0);
        Map<String, Map<String, ActorTimeline>> groups = new LinkedHashMap<>();
        Map<String, String> actorAttributedGroups = new HashMap<>();

        for (EventRow row : rows) {
            if (row.actorId().isBlank()) {
                continue;
            }
            String subjectId = funnelSubjectId(row, journeyKey);
            if (subjectId == null) {
                continue;
            }
            if (firstStep.equals(row.eventType())) {
                if (actorAttributedGroups.containsKey(subjectId)) {
                    continue;
                }
                String groupValue;
                if (groupBy.isBlank()) {
                    groupValue = "all";
                } else if (!row.groupPresent()) {
                    groupValue = "(none)";
                } else {
                    groupValue = normalizeGroupValue(row.groupValue(), groupDataType);
                    if (groupValue == null) {
                        continue;
                    }
                }
                requireDimensionValueWithinBudget(groupValue);
                if (!groups.containsKey(groupValue)
                        && groups.size() >= queryProperties.getMaxFunnelGroups()) {
                    throw funnelDimensionBudgetExceeded();
                }
                // 未指定 journeyKey 时按 actor 首触归因；指定后按一次业务旅程首触归因。
                actorAttributedGroups.put(subjectId, groupValue);
                groups.computeIfAbsent(groupValue, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(subjectId, ignored -> new ActorTimeline())
                        .add(row.eventType(), row.position());
                continue;
            }

            String groupValue = actorAttributedGroups.get(subjectId);
            if (groupValue == null) {
                continue;
            }
            groups.get(groupValue)
                    .computeIfAbsent(subjectId, ignored -> new ActorTimeline())
                    .add(row.eventType(), row.position());
        }
        return groups;
    }

    private String funnelSubjectId(EventRow row, String journeyKey) {
        if (journeyKey.isBlank()) {
            return row.actorId();
        }
        String value = row.journeyValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        requireDimensionValueWithinBudget(value);
        // 同名 flow 也不能跨 actor 合并；actor alias 已在进入这里前归一。
        return row.actorId() + "\0" + value;
    }

    private void requireDimensionValueWithinBudget(String value) {
        if (value.length() > queryProperties.getMaxDimensionValueLength()) {
            throw funnelDimensionBudgetExceeded();
        }
    }

    private BusinessException funnelDimensionBudgetExceeded() {
        return BusinessException.analyticsFunnelDimensionBudgetExceeded(
                queryProperties.getMaxFunnelGroups(),
                queryProperties.getMaxDimensionValueLength()
        );
    }

    private List<AdminFunnelStepResult> calculateFunnelSteps(
            List<String> stepEvents,
            Map<String, ActorTimeline> actors
    ) {
        Set<String> reachedActors = new HashSet<>(actors.keySet());
        Map<String, EventPosition> previousStepPositions = new HashMap<>();
        long firstStepUsers = 0;
        long previousStepUsers = 0;
        List<AdminFunnelStepResult> results = new ArrayList<>();

        for (int index = 0; index < stepEvents.size(); index++) {
            String eventType = stepEvents.get(index);
            Set<String> currentReached = new HashSet<>();
            Map<String, EventPosition> currentStepPositions = new HashMap<>();

            for (String actorId : reachedActors) {
                ActorTimeline timeline = actors.get(actorId);
                EventPosition after = index == 0 ? null : previousStepPositions.get(actorId);
                if (index > 0 && after == null) {
                    continue;
                }
                EventPosition matched = timeline.firstAfter(eventType, after);
                if (matched != null) {
                    currentReached.add(actorId);
                    currentStepPositions.put(actorId, matched);
                }
            }

            long users = currentReached.size();
            if (index == 0) {
                firstStepUsers = users;
            }
            double conversionRate = firstStepUsers == 0 ? 0d : (double) users / (double) firstStepUsers;
            double dropOffRate = index == 0 || previousStepUsers == 0
                    ? 0d
                    : 1d - ((double) users / (double) previousStepUsers);
            results.add(new AdminFunnelStepResult(
                    index + 1,
                    eventType,
                    users,
                    roundRate(conversionRate),
                    roundRate(dropOffRate)
            ));

            reachedActors = currentReached;
            previousStepPositions = currentStepPositions;
            previousStepUsers = users;
        }
        return results;
    }

    private RetentionCounts calculateRetentionCounts(
            Map<String, EventPosition> cohortPositions,
            Map<String, List<EventPosition>> returnPositions,
            int day,
            Instant observationEnd
    ) {
        long eligible = 0;
        long retained = 0;
        for (Map.Entry<String, EventPosition> entry : cohortPositions.entrySet()) {
            EventPosition cohortPosition = entry.getValue();
            Instant cohortTime = cohortPosition.timestamp();
            Instant start = cohortTime
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(day)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            Instant end = start.plus(Duration.ofDays(1));
            // 只有完整走完目标 UTC 自然日的分群成员才能进入该桶分母。
            if (observationEnd.isBefore(end)) {
                continue;
            }
            eligible++;
            List<EventPosition> actorReturnPositions = returnPositions.getOrDefault(entry.getKey(), List.of());
            boolean matched = actorReturnPositions.stream()
                    // 留存只记录入组后的真实回访；D0 不能把同日更早事件或入组事件本身算作回访。
                    .anyMatch(position -> position.compareTo(cohortPosition) > 0
                            && !position.timestamp().isBefore(start)
                            && position.timestamp().isBefore(end));
            if (matched) {
                retained++;
            }
        }
        return new RetentionCounts(eligible, retained);
    }

    private List<EventRow> queryEvents(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String projectId,
            Instant start,
            Instant end,
            List<String> eventTypes,
            String groupBy,
            AnalyticsPropertyDataType groupDataType,
            String journeyKey,
            AnalyticsPropertyFilterService.CompiledPropertyFilters propertyFilters
    ) {
        if (eventTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", eventTypes.stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>();
        String groupProjection = "NULL::text AS group_value";
        String groupPresentProjection = "FALSE AS group_present";
        if (!groupBy.isBlank()) {
            groupProjection = typedGroupProjection(groupDataType);
            groupPresentProjection = "jsonb_exists(properties, ?) AS group_present";
            args.add(groupBy);
            if (groupDataType != null) {
                args.add(groupBy);
                if (groupDataType == AnalyticsPropertyDataType.INTEGER) {
                    args.add(groupBy);
                }
            }
            args.add(groupBy);
        }
        String journeyProjection = "NULL::text AS journey_value";
        if (!journeyKey.isBlank()) {
            // 旧口径只接受字符串 journey id；其他 JSON 类型不能被误当成一次旅程。
            journeyProjection = "CASE WHEN jsonb_typeof(properties -> ?) = 'string' " +
                    "THEN properties ->> ? ELSE NULL END AS journey_value";
            args.add(journeyKey);
            args.add(journeyKey);
        }
        String filterClause = propertyFilters.isEmpty() ? "" : " AND " + propertyFilters.sql();
        String sql = String.format(
                "SELECT id, event_type, event_timestamp, user_id, device_id, %s, %s, %s FROM %s " +
                        "WHERE project_id = ? AND event_timestamp >= ? AND event_timestamp < ? " +
                        "AND event_type IN (%s)%s ORDER BY event_timestamp ASC, id ASC LIMIT ?",
                groupProjection,
                groupPresentProjection,
                journeyProjection,
                eventsTable,
                placeholders,
                filterClause
        );
        args.add(projectId);
        args.add(start.toEpochMilli());
        args.add(end.toEpochMilli());
        args.addAll(eventTypes);
        args.addAll(propertyFilters.arguments());
        args.add(queryProperties.getMaxCandidateRows() + 1);

        long startedAt = System.nanoTime();
        List<EventRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String userId = rs.getString("user_id");
            String deviceId = rs.getString("device_id");
            String actorId = userId == null || userId.isBlank() ? deviceId : userId;
            return new EventRow(
                    rs.getLong("id"),
                    rs.getString("event_type"),
                    Instant.ofEpochMilli(rs.getLong("event_timestamp")),
                    actorId == null ? "" : actorId,
                    rs.getString("group_value"),
                    rs.getBoolean("group_present"),
                    rs.getString("journey_value")
            );
        }, args.toArray());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.log(System.Logger.Level.INFO,
                "Interactive analytics query completed: project={0}, rows={1}, elapsedMs={2}",
                projectId, rows.size(), elapsedMillis);
        if (rows.size() > queryProperties.getMaxCandidateRows()) {
            throw BusinessException.analyticsQueryBudgetExceeded(queryProperties.getMaxCandidateRows());
        }
        return rows;
    }

    private SemanticSelection resolveSelection(String projectId, List<String> semanticKeys) {
        Map<String, List<String>> aliases = semanticDictionaryService.resolveActiveEventAliases(
                projectId,
                semanticKeys
        );
        Map<String, String> rawToSemantic = new LinkedHashMap<>();
        aliases.forEach((semanticKey, rawKeys) -> rawKeys.forEach(rawKey ->
                rawToSemantic.put(rawKey, semanticKey)
        ));
        return new SemanticSelection(List.copyOf(rawToSemantic.keySet()), Map.copyOf(rawToSemantic));
    }

    private static List<EventRow> canonicalize(
            List<EventRow> rows,
            Map<String, String> rawToSemantic
    ) {
        return rows.stream()
                .map(row -> new EventRow(
                        row.id(),
                        rawToSemantic.get(row.eventType()),
                        row.createdAt(),
                        row.actorId(),
                        row.groupValue(),
                        row.groupPresent(),
                        row.journeyValue()
                ))
                .filter(row -> row.eventType() != null)
                .toList();
    }

    private List<EventRow> resolveCanonicalActors(
            JdbcTemplate jdbcTemplate,
            String projectId,
            List<EventRow> rows
    ) {
        String linkTable = dataSourceManager.getTableName(projectId, "actor_identity_links");
        Map<String, String> canonicalActors = actorIdentityResolver.resolveCanonicalActors(
                jdbcTemplate,
                linkTable,
                projectId,
                rows.stream().map(EventRow::actorId).toList()
        );
        return rows.stream()
                .map(row -> new EventRow(
                        row.id(),
                        row.eventType(),
                        row.createdAt(),
                        canonicalActors.getOrDefault(row.actorId(), row.actorId()),
                        row.groupValue(),
                        row.groupPresent(),
                        row.journeyValue()
                ))
                .toList();
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (normalizedProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
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
            return new ProjectContext(projectConfig, dataSourceManager.getDataSource(normalizedProjectId));
        } catch (Exception e) {
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }
    }

    private static List<String> parseEventList(String value, int maxItems, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        List<String> items = new ArrayList<>();
        for (String raw : value.split(",")) {
            String event = requireEventName(raw, fieldName);
            if (!items.contains(event)) {
                items.add(event);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (items.size() > maxItems) {
            throw new IllegalArgumentException(fieldName + " 最多支持 " + maxItems + " 个事件");
        }
        return items;
    }

    private static String requireEventName(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (!normalized.matches("[A-Za-z0-9_.:-]{1,100}")) {
            throw new IllegalArgumentException(fieldName + " 格式无效");
        }
        return normalized;
    }

    private static List<Integer> parseDays(String value) {
        String effective = value == null || value.isBlank() ? "1,7,30" : value;
        List<Integer> result = new ArrayList<>();
        for (String raw : effective.split(",")) {
            int day;
            try {
                day = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("days 格式无效");
            }
            if (day < 0 || day > MAX_RETENTION_DAY) {
                throw new IllegalArgumentException("days 只支持 0-" + MAX_RETENTION_DAY);
            }
            if (!result.contains(day)) {
                result.add(day);
            }
        }
        result.sort(Integer::compareTo);
        return result;
    }

    private static String normalizePropertyKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String key = value.trim();
        if (!key.matches("[A-Za-z0-9_.:-]{1,80}")) {
            throw new IllegalArgumentException("groupBy 格式无效");
        }
        return key;
    }

    private static String typedGroupProjection(AnalyticsPropertyDataType type) {
        if (type == null) {
            return "properties ->> ? AS group_value";
        }
        return switch (type) {
            case STRING -> "CASE WHEN jsonb_typeof(properties -> ?) = 'string' THEN properties ->> ? ELSE NULL END AS group_value";
            case BOOLEAN -> "CASE WHEN jsonb_typeof(properties -> ?) = 'boolean' THEN properties ->> ? ELSE NULL END AS group_value";
            case INTEGER -> "CASE WHEN jsonb_typeof(properties -> ?) = 'number' AND mod((properties ->> ?)::numeric, 1) = 0 THEN properties ->> ? ELSE NULL END AS group_value";
            case NUMBER -> "CASE WHEN jsonb_typeof(properties -> ?) = 'number' THEN properties ->> ? ELSE NULL END AS group_value";
        };
    }

    private static String normalizeGroupValue(String value, AnalyticsPropertyDataType type) {
        if (type == null) {
            return value == null ? null : (value.isBlank() ? "(empty)" : value);
        }
        try {
            return AnalyticsPropertyValueNormalizer.normalize(value, type);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void validateInteractiveRange(Instant start, Instant end) {
        Duration requested = Duration.between(start, end);
        if (requested.compareTo(Duration.ofDays(queryProperties.getMaxRangeDays())) > 0) {
            throw BusinessException.analyticsQueryRangeExceeded(queryProperties.getMaxRangeDays());
        }
    }

    private <T> T executeInteractiveQuery(
            DataSource dataSource,
            java.util.function.Function<JdbcTemplate, T> operation
    ) {
        try {
            return projectTransactions.executeReadOnly(
                    dataSource,
                    queryProperties.getTimeoutSeconds(),
                    operation
            );
        } catch (QueryTimeoutException | TransactionTimedOutException e) {
            throw BusinessException.analyticsQueryTimedOut();
        } catch (DataAccessException e) {
            if (hasSqlState(e, "57014")) {
                throw BusinessException.analyticsQueryTimedOut();
            }
            throw e;
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

    private static double roundRate(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static EventPosition earlier(EventPosition left, EventPosition right) {
        return left.compareTo(right) <= 0 ? left : right;
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

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private record EventRow(
            long id,
            String eventType,
            Instant createdAt,
            String actorId,
            String groupValue,
            boolean groupPresent,
            String journeyValue
    ) {
        EventPosition position() {
            return new EventPosition(createdAt, id);
        }
    }

    private record EventPosition(Instant timestamp, long id) implements Comparable<EventPosition> {
        @Override
        public int compareTo(EventPosition other) {
            int timestampOrder = timestamp.compareTo(other.timestamp);
            return timestampOrder != 0 ? timestampOrder : Long.compare(id, other.id);
        }
    }

    private record RetentionCounts(long eligibleUsers, long retainedUsers) {}

    private record SemanticSelection(List<String> rawKeys, Map<String, String> rawToSemantic) {}

    private static final class ActorTimeline {
        private final Map<String, List<EventPosition>> positionsByEvent = new HashMap<>();

        void add(String eventType, EventPosition position) {
            positionsByEvent.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(position);
        }

        EventPosition firstAfter(String eventType, EventPosition after) {
            List<EventPosition> positions = positionsByEvent.getOrDefault(eventType, List.of());
            for (EventPosition position : positions) {
                if (after == null || position.compareTo(after) > 0) {
                    return position;
                }
            }
            return null;
        }
    }
}
