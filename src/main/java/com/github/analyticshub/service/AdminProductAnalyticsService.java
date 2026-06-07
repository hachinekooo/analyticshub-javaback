package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminFunnelGroupResult;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminFunnelStepResult;
import com.github.analyticshub.dto.AdminRetentionBucket;
import com.github.analyticshub.dto.AdminRetentionResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
    private static final String FUNNEL_ATTRIBUTION_MODEL = "first_touch_actor";

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    public AdminProductAnalyticsService(MultiDataSourceManager dataSourceManager, ObjectMapper objectMapper) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
    }

    public AdminFunnelResponse getFunnel(
            String projectId,
            String from,
            String to,
            String steps,
            String groupBy
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        List<String> stepEvents = parseEventList(steps, MAX_FUNNEL_STEPS, "steps");
        String normalizedGroupBy = normalizePropertyKey(groupBy);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(requireProject(normalizedProjectId).dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        List<EventRow> rows = queryEvents(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                range.start(),
                range.end(),
                stepEvents
        );

        Map<String, Map<String, ActorTimeline>> groups = buildFunnelGroups(rows, stepEvents, normalizedGroupBy);
        List<AdminFunnelGroupResult> groupResults = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AdminFunnelGroupResult(
                        entry.getKey(),
                        calculateFunnelSteps(stepEvents, entry.getValue())
                ))
                .toList();

        return new AdminFunnelResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                stepEvents,
                normalizedGroupBy,
                FUNNEL_ATTRIBUTION_MODEL,
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
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        String normalizedCohortEvent = requireEventName(cohortEvent, "cohortEvent");
        String normalizedReturnEvent = requireEventName(returnEvent, "returnEvent");
        List<Integer> retentionDays = parseDays(days);
        int maxDay = retentionDays.stream().max(Integer::compareTo).orElse(30);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(requireProject(normalizedProjectId).dataSource());
        String eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        List<EventRow> rows = queryEvents(
                jdbcTemplate,
                eventsTable,
                normalizedProjectId,
                range.start(),
                range.end().plus(Duration.ofDays(maxDay + 1L)),
                List.of(normalizedCohortEvent, normalizedReturnEvent)
        );

        Map<String, Instant> cohortTimes = new HashMap<>();
        Map<String, List<Instant>> returnTimes = new HashMap<>();
        for (EventRow row : rows) {
            if (row.actorId().isBlank()) {
                continue;
            }
            if (normalizedCohortEvent.equals(row.eventType())
                    && !row.createdAt().isBefore(range.start())
                    && row.createdAt().isBefore(range.end())) {
                cohortTimes.merge(row.actorId(), row.createdAt(), AdminProductAnalyticsService::earlier);
            }
            if (normalizedReturnEvent.equals(row.eventType())) {
                returnTimes.computeIfAbsent(row.actorId(), ignored -> new ArrayList<>()).add(row.createdAt());
            }
        }
        returnTimes.values().forEach(times -> times.sort(Comparator.naturalOrder()));

        long cohortUsers = cohortTimes.size();
        List<AdminRetentionBucket> buckets = retentionDays.stream()
                .map(day -> {
                    long retained = countRetainedUsers(cohortTimes, returnTimes, day);
                    double rate = cohortUsers == 0 ? 0d : (double) retained / (double) cohortUsers;
                    return new AdminRetentionBucket(day, retained, roundRate(rate));
                })
                .toList();

        return new AdminRetentionResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                normalizedCohortEvent,
                normalizedReturnEvent,
                cohortUsers,
                buckets
        );
    }

    private Map<String, Map<String, ActorTimeline>> buildFunnelGroups(
            List<EventRow> rows,
            List<String> stepEvents,
            String groupBy
    ) {
        String firstStep = stepEvents.get(0);
        Map<String, Map<String, ActorTimeline>> groups = new LinkedHashMap<>();
        Map<String, String> actorAttributedGroups = new HashMap<>();

        for (EventRow row : rows) {
            if (row.actorId().isBlank()) {
                continue;
            }
            if (firstStep.equals(row.eventType())) {
                if (actorAttributedGroups.containsKey(row.actorId())) {
                    continue;
                }
                String groupValue = groupBy.isBlank() ? "all" : propertyValue(row.properties(), groupBy);
                // 分组漏斗采用 first-touch 归因：同一 actor 只归入第一次进入漏斗的 group。
                // 否则一个用户多次从不同入口看付费墙，后续购买会被多个入口重复计数。
                actorAttributedGroups.put(row.actorId(), groupValue);
                groups.computeIfAbsent(groupValue, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(row.actorId(), ignored -> new ActorTimeline())
                        .add(row.eventType(), row.createdAt());
                continue;
            }

            String groupValue = actorAttributedGroups.get(row.actorId());
            if (groupValue == null) {
                continue;
            }
            groups.get(groupValue)
                    .computeIfAbsent(row.actorId(), ignored -> new ActorTimeline())
                    .add(row.eventType(), row.createdAt());
        }
        return groups;
    }

    private List<AdminFunnelStepResult> calculateFunnelSteps(
            List<String> stepEvents,
            Map<String, ActorTimeline> actors
    ) {
        Set<String> reachedActors = new HashSet<>(actors.keySet());
        Map<String, Instant> previousStepTimes = new HashMap<>();
        long firstStepUsers = 0;
        long previousStepUsers = 0;
        List<AdminFunnelStepResult> results = new ArrayList<>();

        for (int index = 0; index < stepEvents.size(); index++) {
            String eventType = stepEvents.get(index);
            Set<String> currentReached = new HashSet<>();
            Map<String, Instant> currentStepTimes = new HashMap<>();

            for (String actorId : reachedActors) {
                ActorTimeline timeline = actors.get(actorId);
                Instant after = index == 0 ? Instant.EPOCH : previousStepTimes.get(actorId);
                if (after == null) {
                    continue;
                }
                Instant matched = timeline.firstAtOrAfter(eventType, after);
                if (matched != null) {
                    currentReached.add(actorId);
                    currentStepTimes.put(actorId, matched);
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
            previousStepTimes = currentStepTimes;
            previousStepUsers = users;
        }
        return results;
    }

    private long countRetainedUsers(
            Map<String, Instant> cohortTimes,
            Map<String, List<Instant>> returnTimes,
            int day
    ) {
        long retained = 0;
        for (Map.Entry<String, Instant> entry : cohortTimes.entrySet()) {
            Instant start = entry.getValue().plus(Duration.ofDays(day));
            Instant end = start.plus(Duration.ofDays(1));
            List<Instant> actorReturnTimes = returnTimes.getOrDefault(entry.getKey(), List.of());
            boolean matched = actorReturnTimes.stream()
                    .anyMatch(time -> !time.isBefore(start) && time.isBefore(end));
            if (matched) {
                retained++;
            }
        }
        return retained;
    }

    private List<EventRow> queryEvents(
            JdbcTemplate jdbcTemplate,
            String eventsTable,
            String projectId,
            Instant start,
            Instant end,
            List<String> eventTypes
    ) {
        String placeholders = String.join(",", eventTypes.stream().map(ignored -> "?").toList());
        String sql = String.format(
                "SELECT event_type, created_at, user_id, device_id, properties FROM %s " +
                        "WHERE project_id = ? AND created_at >= ? AND created_at < ? " +
                        "AND event_type IN (%s) ORDER BY created_at ASC",
                eventsTable,
                placeholders
        );
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(Timestamp.from(start));
        args.add(Timestamp.from(end));
        args.addAll(eventTypes);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String properties = rs.getString("properties");
            JsonNode propertiesNode = null;
            if (properties != null && !properties.isBlank()) {
                try {
                    propertiesNode = objectMapper.readTree(properties);
                } catch (Exception e) {
                    log.log(System.Logger.Level.WARNING, "Failed to parse analytics properties JSON", e);
                }
            }
            String userId = rs.getString("user_id");
            String deviceId = rs.getString("device_id");
            String actorId = userId == null || userId.isBlank() ? deviceId : userId;
            return new EventRow(
                    rs.getString("event_type"),
                    rs.getTimestamp("created_at").toInstant(),
                    actorId == null ? "" : actorId,
                    propertiesNode
            );
        }, args.toArray());
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

    private static String propertyValue(JsonNode properties, String key) {
        if (properties == null || key.isBlank()) {
            return "all";
        }
        JsonNode value = properties.get(key);
        if (value == null || value.isNull()) {
            return "(none)";
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        return text == null || text.isBlank() ? "(empty)" : text;
    }

    private static double roundRate(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
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

    private record EventRow(String eventType, Instant createdAt, String actorId, JsonNode properties) {}

    private static final class ActorTimeline {
        private final Map<String, List<Instant>> timesByEvent = new HashMap<>();

        void add(String eventType, Instant createdAt) {
            timesByEvent.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(createdAt);
        }

        Instant firstAtOrAfter(String eventType, Instant after) {
            List<Instant> times = timesByEvent.getOrDefault(eventType, List.of());
            for (Instant time : times) {
                if (!time.isBefore(after)) {
                    return time;
                }
            }
            return null;
        }
    }
}
