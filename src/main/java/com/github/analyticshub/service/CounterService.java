package com.github.analyticshub.service;

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

@Service
public class CounterService {

    private final MultiDataSourceManager dataSourceManager;

    public CounterService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public CountersResponse list(String projectId, boolean onlyPublic) {
        String normalizedProjectId = normalizeProjectId(projectId);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());
        String table = dataSourceManager.getTableName(normalizedProjectId, "counters");
        ensureCountersTable(jdbcTemplate, table);

        String sql = String.format(
                "SELECT counter_key, counter_value, display_name, unit, is_public, description, updated_at " +
                        "FROM %s WHERE project_id = ? %s ORDER BY updated_at DESC",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );

        List<CounterRecord> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new CounterRecord(
                                rs.getString("counter_key"),
                                rs.getLong("counter_value"),
                                rs.getString("display_name"),
                                rs.getString("unit"),
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
                "SELECT counter_key, counter_value, display_name, unit, is_public, description, updated_at " +
                        "FROM %s WHERE project_id = ? AND counter_key = ? %s",
                table,
                onlyPublic ? "AND is_public = TRUE" : ""
        );

        List<CounterRecord> items = jdbcTemplate.query(sql, (rs, rowNum) ->
                        new CounterRecord(
                                rs.getString("counter_key"),
                                rs.getLong("counter_value"),
                                rs.getString("display_name"),
                                rs.getString("unit"),
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
                "INSERT INTO %s (counter_key, counter_value, display_name, unit, is_public, description, project_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (project_id, counter_key) DO UPDATE SET " +
                        "counter_value = COALESCE(EXCLUDED.counter_value, %s.counter_value), " +
                        "display_name = COALESCE(EXCLUDED.display_name, %s.display_name), " +
                        "unit = COALESCE(EXCLUDED.unit, %s.unit), " +
                        "is_public = COALESCE(EXCLUDED.is_public, %s.is_public), " +
                        "description = COALESCE(EXCLUDED.description, %s.description), " +
                        "updated_at = EXCLUDED.updated_at",
                table,
                table,
                table,
                table,
                table,
                table
        );

        jdbcTemplate.update(
                upsertSql,
                key,
                request != null ? request.value() : null,
                request != null ? request.displayName() : null,
                request != null ? request.unit() : null,
                request != null ? request.isPublic() : null,
                request != null ? request.description() : null,
                normalizedProjectId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        CounterRecord record = get(normalizedProjectId, key, false);
        if (record == null) {
            throw new BusinessException("COUNTER_UPSERT_FAILED", "计数器写入失败");
        }
        return record;
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
                table,
                table
        );

        jdbcTemplate.update(
                upsertSql,
                key,
                delta,
                normalizedProjectId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        CounterRecord record = get(normalizedProjectId, key, false);
        if (record == null) {
            throw new BusinessException("COUNTER_INCREMENT_FAILED", "计数器累加失败");
        }
        return record;
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

    private static void ensureCountersTable(JdbcTemplate jdbcTemplate, String table) {
        String constraintName = safeDbIdentifier("uq_" + table + "_project_key", 63);
        String createSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "counter_key VARCHAR(100) NOT NULL, " +
                        "counter_value BIGINT NOT NULL DEFAULT 0, " +
                        "display_name VARCHAR(200), " +
                        "unit VARCHAR(50), " +
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

        String indexName = safeDbIdentifier("idx_" + table + "_project_updated", 63);
        String indexSql = String.format(
                "CREATE INDEX IF NOT EXISTS %s ON %s(project_id, updated_at DESC)",
                indexName,
                table
        );
        jdbcTemplate.execute(indexSql);
    }

    private static String safeDbIdentifier(String value, int maxLen) {
        String normalized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9_]", "_");
        if (normalized.isEmpty()) {
            return "x";
        }
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        String suffix = CryptoUtils.sha256Hex(normalized).substring(0, 8);
        int keep = Math.max(1, maxLen - 9);
        return normalized.substring(0, keep) + "_" + suffix;
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
}
