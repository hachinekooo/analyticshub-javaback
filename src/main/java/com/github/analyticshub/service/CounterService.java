package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class CounterService {

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    public CounterService(MultiDataSourceManager dataSourceManager, ObjectMapper objectMapper) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
    }

    public CountersResponse list(String projectId, boolean onlyPublic) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        String sql = String.format(
                "SELECT counter_key, counter_value, display_name, unit, event_trigger, is_public, description, updated_at " +
                        "FROM %s WHERE project_id = ? %s ORDER BY updated_at DESC",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );

        List<CounterRecord> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new CounterRecord(
                                rs.getString("counter_key"),
                                rs.getLong("counter_value"),
                                parseJson(rs.getString("display_name")),
                                parseJson(rs.getString("unit")),
                                parseJson(rs.getString("event_trigger")),
                                rs.getBoolean("is_public"),
                                rs.getString("description"),
                                rs.getTimestamp("updated_at").toInstant().toString()
                        ),
                normalizedProjectId
        );

        return new CountersResponse(normalizedProjectId, items);
    }

    public CounterRecord get(String projectId, String key, boolean onlyPublic) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        String sql = String.format(
                "SELECT counter_key, counter_value, display_name, unit, event_trigger, is_public, description, updated_at " +
                        "FROM %s WHERE project_id = ? AND counter_key = ? %s",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );

        List<CounterRecord> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new CounterRecord(
                                rs.getString("counter_key"),
                                rs.getLong("counter_value"),
                                parseJson(rs.getString("display_name")),
                                parseJson(rs.getString("unit")),
                                parseJson(rs.getString("event_trigger")),
                                rs.getBoolean("is_public"),
                                rs.getString("description"),
                                rs.getTimestamp("updated_at").toInstant().toString()
                        ),
                normalizedProjectId,
                key
        );
        return items.isEmpty() ? null : items.getFirst();
    }

    @Transactional
    public CounterRecord upsert(String projectId, String key, CounterUpsertRequest request) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        Instant now = Instant.now();
        String upsertSql = String.format(
                "INSERT INTO %s (counter_key, counter_value, display_name, unit, event_trigger, is_public, description, project_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (project_id, counter_key) DO UPDATE SET " +
                        "counter_value = COALESCE(EXCLUDED.counter_value, %s.counter_value), " +
                        "display_name = COALESCE(EXCLUDED.display_name, %s.display_name), " +
                        "unit = COALESCE(EXCLUDED.unit, %s.unit), " +
                        "event_trigger = COALESCE(EXCLUDED.event_trigger, %s.event_trigger), " +
                        "is_public = COALESCE(EXCLUDED.is_public, %s.is_public), " +
                        "description = COALESCE(EXCLUDED.description, %s.description), " +
                        "updated_at = EXCLUDED.updated_at",
                table, table, table, table, table, table, table
        );

        jdbcTemplate.update(
                upsertSql,
                key,
                request != null ? request.value() : null,
                request != null ? toJsonString(request.displayName()) : null,
                request != null ? toJsonString(request.unit()) : null,
                request != null ? toJsonString(request.eventTrigger()) : null,
                request != null ? request.isPublic() : null,
                request != null ? request.description() : null,
                normalizedProjectId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        return get(normalizedProjectId, key, false);
    }

    @Transactional
    public CounterRecord increment(String projectId, String key, long delta) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        Instant now = Instant.now();
        String upsertSql = String.format(
                "INSERT INTO %s (counter_key, counter_value, project_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT (project_id, counter_key) DO UPDATE SET " +
                        "counter_value = %s.counter_value + EXCLUDED.counter_value, " +
                        "updated_at = EXCLUDED.updated_at",
                table, table
        );

        jdbcTemplate.update(upsertSql, key, delta, normalizedProjectId, Timestamp.from(now), Timestamp.from(now));
        return get(normalizedProjectId, key, false);
    }

    @Transactional
    public void delete(String projectId, String key) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        String sql = String.format("DELETE FROM %s WHERE project_id = ? AND counter_key = ?", table);
        jdbcTemplate.update(sql, normalizedProjectId, key);
    }

    /**
     * 根据事件自动增加计数器 (Lambda 引擎)
     */
    @Transactional
    public void processEventAutoIncrements(String projectId, String eventType, Map<String, Object> properties) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        // 查找匹配此 eventType 的所有规则
        String sql = String.format("SELECT counter_key, event_trigger FROM %s WHERE project_id = ? AND event_trigger IS NOT NULL", table);
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(sql, normalizedProjectId);

        for (Map<String, Object> rule : rules) {
            String counterKey = (String) rule.get("counter_key");
            JsonNode trigger = parseJson((String) rule.get("event_trigger"));
            
            if (trigger != null && isMatch(trigger, eventType, properties)) {
                increment(normalizedProjectId, counterKey, 1L);
            }
        }
    }

    boolean isMatch(JsonNode trigger, String eventType, Map<String, Object> properties) {
        if (!trigger.has("event_type")) return false;
        if (!trigger.get("event_type").asText().equals(eventType)) return false;

        // 条件过滤 (可选)
        if (trigger.has("conditions")) {
            JsonNode conditions = trigger.get("conditions");
            if (properties == null) return false;
            for (Map.Entry<String, JsonNode> entry : (Iterable<Map.Entry<String, JsonNode>>) () -> conditions.fields()) {
                Object val = properties.get(entry.getKey());
                if (val == null || !val.toString().equals(entry.getValue().asText())) {
                    return false;
                }
            }
        }
        return true;
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
            return null;
        }
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        MultiDataSourceManager.ProjectConfig projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        if (projectConfig == null) throw BusinessException.invalidProject(normalizedProjectId);
        DataSource dataSource = dataSourceManager.getDataSource(normalizedProjectId);
        return new ProjectContext(projectConfig, dataSource);
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private static void ensureCountersTable(JdbcTemplate jdbcTemplate, String table) {
        String constraintName = safeDbIdentifier("uq_" + table + "_project_key", 63);
        String createSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "counter_key VARCHAR(100) NOT NULL, " +
                        "counter_value BIGINT NOT NULL DEFAULT 0, " +
                        "display_name JSONB, " +
                        "unit JSONB, " +
                        "event_trigger JSONB, " +
                        "is_public BOOLEAN DEFAULT FALSE, " +
                        "description TEXT, " +
                        "project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system', " +
                        "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), " +
                        "updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), " +
                        "CONSTRAINT %s UNIQUE (project_id, counter_key)" +
                        ")",
                table,
                constraintName
        );
        jdbcTemplate.execute(createSql);
    }

    private static String safeDbIdentifier(String value, int maxLen) {
        String normalized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9_]", "_");
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, maxLen);
    }

    private static String normalizeProjectId(String projectId) {
        return projectId == null ? "" : projectId.strip();
    }
}
