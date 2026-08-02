package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.CounterHistoryMode;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class CounterService {

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ProjectTransactionExecutor projectTransactions;
    private final SemanticDictionaryService semanticDictionaryService;

    public CounterService(
            MultiDataSourceManager dataSourceManager,
            ObjectMapper objectMapper,
            ProjectTransactionExecutor projectTransactions,
            SemanticDictionaryService semanticDictionaryService
    ) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.projectTransactions = projectTransactions;
        this.semanticDictionaryService = semanticDictionaryService;
    }

    public CountersResponse list(String projectId, boolean onlyPublic) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");

        String sql = String.format(
                "SELECT counter_key, counter_value, display_name, unit, event_trigger, is_public, description, " +
                        "updated_at, last_rebuilt_at, last_rebuild_event_count, rebuild_offset, event_count_start_at " +
                        "FROM %s WHERE project_id = ? %s ORDER BY updated_at DESC",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );

        List<CounterRecord> items = jdbcTemplate.query(sql, (rs, rowNum) -> mapCounter(rs),
                normalizedProjectId
        );

        return new CountersResponse(normalizedProjectId, items);
    }

    public CounterRecord get(String projectId, String key, boolean onlyPublic) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedKey = normalizeCounterKey(key);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        return findCounter(jdbcTemplate, table, normalizedProjectId, normalizedKey, onlyPublic);
    }

    public CounterRecord upsert(String projectId, String key, CounterUpsertRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedKey = normalizeCounterKey(key);
        ProjectContext context = requireProject(normalizedProjectId);
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        Long value = request == null ? null : request.value();
        Boolean isPublic = request == null ? null : request.isPublic();
        String displayName = request == null ? null : toJsonString(request.displayName());
        String unit = request == null ? null : toJsonString(request.unit());
        CounterEventTriggerRule normalizedTrigger = request == null
                ? null
                : validateAndNormalizeEventTrigger(request.eventTrigger());
        if (normalizedTrigger != null) {
            semanticDictionaryService.resolveActiveEventAliases(
                    normalizedProjectId,
                    normalizedTrigger.semanticKeys()
            );
        }
        boolean clearEventTrigger = request != null && Boolean.TRUE.equals(request.clearEventTrigger());
        if (clearEventTrigger && normalizedTrigger != null) {
            throw CounterEventTriggerRule.invalid(
                    "eventTrigger 与 clearEventTrigger=true 不能同时传递"
            );
        }
        String eventTrigger = normalizedTrigger == null
                ? null
                : toJsonString(normalizedTrigger.normalizedJson());
        String description = request == null ? null : request.description();
        Long rebuildOffset = request == null ? null : request.rebuildOffset();
        CounterHistoryMode historyMode = request == null ? null : request.historyMode();
        boolean historyModeProvided = historyMode != null;
        boolean includeExisting = historyMode == CounterHistoryMode.INCLUDE_EXISTING;
        boolean resetRebuildMetadata = value != null || eventTrigger != null || clearEventTrigger
                || rebuildOffset != null || historyModeProvided;

        return projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            Instant now = Instant.now();
            String upsertSql = String.format(
                    "INSERT INTO %s (counter_key, counter_value, display_name, unit, event_trigger, is_public, description, " +
                            "project_id, rebuild_offset, event_count_start_at, created_at, updated_at) " +
                            "VALUES (?, COALESCE(?, 0), ?::jsonb, ?::jsonb, ?::jsonb, COALESCE(?, FALSE), ?, ?, " +
                            "COALESCE(?, 0), CASE WHEN ? THEN statement_timestamp() ELSE NULL END, ?, ?) " +
                            "ON CONFLICT (project_id, counter_key) DO UPDATE SET " +
                            "counter_value = COALESCE(?, %s.counter_value), " +
                            "display_name = COALESCE(EXCLUDED.display_name, %s.display_name), " +
                            "unit = COALESCE(EXCLUDED.unit, %s.unit), " +
                            "event_trigger = CASE WHEN ? THEN NULL " +
                            "ELSE COALESCE(EXCLUDED.event_trigger, %s.event_trigger) END, " +
                            "is_public = COALESCE(?, %s.is_public), " +
                            "description = COALESCE(EXCLUDED.description, %s.description), " +
                            "rebuild_offset = COALESCE(?, %s.rebuild_offset), " +
                            "event_count_start_at = CASE " +
                            "WHEN NOT ? THEN %s.event_count_start_at " +
                            "WHEN ? THEN NULL " +
                            "WHEN %s.event_count_start_at IS NULL THEN EXCLUDED.event_count_start_at " +
                            "ELSE %s.event_count_start_at END, " +
                            "last_rebuilt_at = CASE WHEN ? " +
                            "THEN NULL ELSE %s.last_rebuilt_at END, " +
                            "last_rebuild_event_count = CASE WHEN ? " +
                            "THEN NULL ELSE %s.last_rebuild_event_count END, " +
                            "updated_at = EXCLUDED.updated_at",
                    table, table, table, table, table, table, table, table, table, table, table, table, table
            );

            jdbcTemplate.update(
                    upsertSql,
                    normalizedKey,
                    value,
                    displayName,
                    unit,
                    eventTrigger,
                    isPublic,
                    description,
                    normalizedProjectId,
                    rebuildOffset,
                    historyMode == CounterHistoryMode.START_FROM_NOW,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    value,
                    clearEventTrigger,
                    isPublic,
                    rebuildOffset,
                    historyModeProvided,
                    includeExisting,
                    resetRebuildMetadata,
                    resetRebuildMetadata
            );
            return findCounter(jdbcTemplate, table, normalizedProjectId, normalizedKey, false);
        });
    }

    public CounterRecord increment(String projectId, String key, long delta) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedKey = normalizeCounterKey(key);
        ProjectContext context = requireProject(normalizedProjectId);
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");

        return projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            incrementValue(jdbcTemplate, table, normalizedProjectId, normalizedKey, delta);
            return findCounter(jdbcTemplate, table, normalizedProjectId, normalizedKey, false);
        });
    }

    /**
     * Rebuilds an event-driven counter from its persisted history policy.
     *
     * <p>The counter row is locked before the historical count starts. Live
     * event projection takes the same row lock, so a concurrent event is
     * counted either by this snapshot or by the subsequent increment, never
     * lost by the replacement update.</p>
     */
    public CounterRecord rebuild(String projectId, String key) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedKey = normalizeCounterKey(key);
        ProjectContext context = requireProject(normalizedProjectId);
        String counterTable = dataSourceManager.getTableName(normalizedProjectId, "counters");
        String eventTable = dataSourceManager.getTableName(normalizedProjectId, "events");

        return projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            String lockSql = String.format(
                    "SELECT event_trigger, rebuild_offset, event_count_start_at " +
                            "FROM %s WHERE project_id = ? AND counter_key = ? FOR UPDATE",
                    counterTable
            );
            List<CounterRebuildPolicy> storedPolicies = jdbcTemplate.query(
                    lockSql,
                    (rs, rowNum) -> new CounterRebuildPolicy(
                            rs.getString("event_trigger"),
                            rs.getLong("rebuild_offset"),
                            rs.getTimestamp("event_count_start_at")
                    ),
                    normalizedProjectId,
                    normalizedKey
            );
            if (storedPolicies.isEmpty()) {
                throw new BusinessException(
                        "COUNTER_NOT_FOUND",
                        "计数器不存在",
                        HttpStatus.NOT_FOUND
                );
            }

            CounterRebuildPolicy policy = storedPolicies.getFirst();
            JsonNode storedTrigger = parseJson(policy.eventTriggerJson());
            if (storedTrigger == null || storedTrigger.isNull()) {
                throw new BusinessException(
                        "COUNTER_REBUILD_RULE_REQUIRED",
                        "计数器没有 eventTrigger，无法执行历史回算",
                        HttpStatus.BAD_REQUEST
                );
            }
            CounterEventTriggerRule trigger = validateAndNormalizeEventTrigger(storedTrigger);
            StringJoiner predicates = new StringJoiner(" OR ", "(", ")");
            List<Object> arguments = new ArrayList<>(1 + trigger.clauses().size() * 2);
            arguments.add(normalizedProjectId);
            if (policy.eventCountStartAt() != null) {
                arguments.add(policy.eventCountStartAt());
            }
            Map<String, List<String>> aliases = semanticDictionaryService.resolveActiveEventAliases(
                    normalizedProjectId,
                    trigger.semanticKeys()
            );
            for (CounterEventTriggerRule.Clause clause : trigger.clauses()) {
                for (String rawKey : aliases.get(clause.semanticKey())) {
                    if (clause.conditions() == null) {
                        predicates.add("event_type = ?");
                    } else {
                        predicates.add("(event_type = ? AND properties @> ?::jsonb)");
                    }
                    arguments.add(rawKey);
                    if (clause.conditions() != null) {
                        arguments.add(toJsonString(clause.conditions()));
                    }
                }
            }
            Long matchingEvents = 0L;
            if (predicates.length() > 2) {
                String countSql = String.format(
                        "SELECT COUNT(*) FROM %s WHERE project_id = ? %s AND %s",
                        eventTable,
                        policy.eventCountStartAt() == null ? "" : "AND created_at >= ?",
                        predicates
                );
                matchingEvents = jdbcTemplate.queryForObject(
                        countSql,
                        Long.class,
                        arguments.toArray()
                );
            }

            long matchedEventCount = matchingEvents == null ? 0L : matchingEvents;
            long rebuiltValue;
            try {
                rebuiltValue = Math.addExact(matchedEventCount, policy.rebuildOffset());
            } catch (ArithmeticException exception) {
                throw new BusinessException(
                        "COUNTER_VALUE_OVERFLOW",
                        "历史事件数与基础调整值相加后超出 BIGINT 范围",
                        HttpStatus.BAD_REQUEST
                );
            }
            Instant now = Instant.now();
            String updateSql = String.format(
                    "UPDATE %s SET counter_value = ?, last_rebuilt_at = ?, " +
                            "last_rebuild_event_count = ?, updated_at = ? " +
                            "WHERE project_id = ? AND counter_key = ?",
                    counterTable
            );
            jdbcTemplate.update(
                    updateSql,
                    rebuiltValue,
                    Timestamp.from(now),
                    matchedEventCount,
                    Timestamp.from(now),
                    normalizedProjectId,
                    normalizedKey
            );
            return findCounter(jdbcTemplate, counterTable, normalizedProjectId, normalizedKey, false);
        });
    }

    public void delete(String projectId, String key) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedKey = normalizeCounterKey(key);
        ProjectContext context = requireProject(normalizedProjectId);
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            String sql = String.format("DELETE FROM %s WHERE project_id = ? AND counter_key = ?", table);
            jdbcTemplate.update(sql, normalizedProjectId, normalizedKey);
            return null;
        });
    }

    /**
     * 根据事件自动增加计数器 (Lambda 引擎)
     */
    public void processEventAutoIncrements(String projectId, String eventType, Map<String, Object> properties) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String semanticKey = semanticDictionaryService.resolveActiveEventSemanticKey(
                normalizedProjectId,
                eventType
        );
        if (semanticKey == null) {
            return;
        }
        ProjectContext context = requireProject(normalizedProjectId);
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");

        projectTransactions.execute(context.dataSource(), jdbcTemplate -> {
            String sql = String.format(
                    "SELECT counter_key, event_trigger FROM %s " +
                    "WHERE project_id = ? AND event_trigger IS NOT NULL " +
                            "AND (event_trigger->>'semantic_key' = ? " +
                            "OR event_trigger->'semantic_keys' @> to_jsonb(ARRAY[?]::text[]) " +
                            "OR event_trigger->'any_of' @> ?::jsonb) " +
                            "ORDER BY counter_key FOR UPDATE",
                    table
            );
            String anyOfProbe = toJsonString(List.of(Map.of("semantic_key", semanticKey)));
            List<CounterRule> rules = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> new CounterRule(
                            rs.getString("counter_key"),
                            rs.getString("event_trigger")
                    ),
                    normalizedProjectId,
                    semanticKey,
                    semanticKey,
                    anyOfProbe
            );

            for (CounterRule rule : rules) {
                JsonNode trigger = parseJson(rule.eventTriggerJson());

                if (trigger != null && isMatch(trigger, semanticKey, properties)) {
                    incrementValue(jdbcTemplate, table, normalizedProjectId, rule.counterKey(), 1L);
                }
            }
            return null;
        });
    }

    boolean isMatch(JsonNode trigger, String semanticKey, Map<String, Object> properties) {
        if (trigger == null) {
            return false;
        }
        CounterEventTriggerRule normalizedTrigger;
        try {
            normalizedTrigger = validateAndNormalizeEventTrigger(trigger);
        } catch (BusinessException exception) {
            // A malformed legacy rule must not make event collection fail.
            return false;
        }
        JsonNode propertyNode = properties == null ? null : objectMapper.valueToTree(properties);
        return normalizedTrigger.matches(semanticKey, propertyNode);
    }

    private CounterEventTriggerRule validateAndNormalizeEventTrigger(JsonNode trigger) {
        if (trigger == null) return null;
        return CounterEventTriggerRule.parse(trigger);
    }

    private JsonNode parseJson(String json) {
        try {
            return (json == null || json.isBlank()) ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJsonString(Object obj) {
        try {
            return obj == null ? null : objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(
                    "INVALID_COUNTER_JSON",
                    "计数器配置无法序列化",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private CounterRecord findCounter(
            JdbcTemplate jdbcTemplate,
            String table,
            String projectId,
            String key,
            boolean onlyPublic
    ) {
        String sql = String.format(
                "SELECT counter_key, counter_value, display_name, unit, event_trigger, is_public, description, " +
                        "updated_at, last_rebuilt_at, last_rebuild_event_count, rebuild_offset, event_count_start_at " +
                        "FROM %s WHERE project_id = ? AND counter_key = ? %s",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );
        List<CounterRecord> items = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapCounter(rs),
                projectId,
                key
        );
        return items.isEmpty() ? null : items.getFirst();
    }

    private CounterRecord mapCounter(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp lastRebuiltAt = rs.getTimestamp("last_rebuilt_at");
        Timestamp eventCountStartAt = rs.getTimestamp("event_count_start_at");
        Number lastRebuildEventCount = (Number) rs.getObject("last_rebuild_event_count");
        return new CounterRecord(
                rs.getString("counter_key"),
                rs.getLong("counter_value"),
                parseJson(rs.getString("display_name")),
                parseJson(rs.getString("unit")),
                parseJson(rs.getString("event_trigger")),
                rs.getBoolean("is_public"),
                rs.getString("description"),
                updatedAt == null ? null : updatedAt.toInstant().toString(),
                lastRebuiltAt == null ? null : lastRebuiltAt.toInstant().toString(),
                lastRebuildEventCount == null ? null : lastRebuildEventCount.longValue(),
                rs.getLong("rebuild_offset"),
                eventCountStartAt == null
                        ? CounterHistoryMode.INCLUDE_EXISTING
                        : CounterHistoryMode.START_FROM_NOW,
                eventCountStartAt == null ? null : eventCountStartAt.toInstant().toString()
        );
    }

    private static void incrementValue(
            JdbcTemplate jdbcTemplate,
            String table,
            String projectId,
            String key,
            long delta
    ) {
        Instant now = Instant.now();
        String upsertSql = String.format(
                "INSERT INTO %s (counter_key, counter_value, project_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT (project_id, counter_key) DO UPDATE SET " +
                        "counter_value = %s.counter_value + EXCLUDED.counter_value, " +
                        "updated_at = EXCLUDED.updated_at",
                table,
                table
        );
        jdbcTemplate.update(upsertSql, key, delta, projectId, Timestamp.from(now), Timestamp.from(now));
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        MultiDataSourceManager.ProjectConfig projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        if (projectConfig == null) throw BusinessException.invalidProject(normalizedProjectId);
        DataSource dataSource = dataSourceManager.getDataSource(normalizedProjectId);
        return new ProjectContext(projectConfig, dataSource);
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private record CounterRule(String counterKey, String eventTriggerJson) {}

    private record CounterRebuildPolicy(
            String eventTriggerJson,
            long rebuildOffset,
            Timestamp eventCountStartAt
    ) {}

    private static String normalizeProjectId(String projectId) {
        return projectId == null ? "" : projectId.strip();
    }

    private static String normalizeCounterKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("counter key 不能为空");
        }
        String normalized = key.strip();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("counter key 长度不能超过 100");
        }
        if (!normalized.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$")) {
            throw new IllegalArgumentException(
                    "counter key 仅支持字母、数字、点、下划线、冒号和连字符，且必须以字母或数字开头"
            );
        }
        return normalized;
    }
}
