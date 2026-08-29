package com.github.analyticshub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.dto.AdminDashboardRecord;
import com.github.analyticshub.dto.AdminDashboardUpsertRequest;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AdminDashboardService {

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{1,50}$");
    private static final Pattern DASHBOARD_KEY_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final TypeReference<LinkedHashMap<String, String>> DISPLAY_NAME_TYPE =
            new TypeReference<>() { };
    private static final TypeReference<LinkedHashMap<String, Object>> DEFINITION_TYPE =
            new TypeReference<>() { };

    private final AnalyticsProjectMapper projectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DashboardDefinitionValidator definitionValidator;
    private final DashboardOverviewMetricPolicy overviewMetricPolicy;
    private final DashboardCounterPolicy counterPolicy;
    private final DashboardGovernedMetricPolicy governedMetricPolicy;
    private final AnalysisPackOwnershipService analyticsWriteLock;

    public AdminDashboardService(
            AnalyticsProjectMapper projectMapper,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DashboardDefinitionValidator definitionValidator,
            DashboardOverviewMetricPolicy overviewMetricPolicy,
            DashboardCounterPolicy counterPolicy,
            DashboardGovernedMetricPolicy governedMetricPolicy,
            AnalysisPackOwnershipService analyticsWriteLock
    ) {
        this.projectMapper = projectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.definitionValidator = definitionValidator;
        this.overviewMetricPolicy = overviewMetricPolicy;
        this.counterPolicy = counterPolicy;
        this.governedMetricPolicy = governedMetricPolicy;
        this.analyticsWriteLock = analyticsWriteLock;
    }

    public List<AdminDashboardRecord> list(String projectId) {
        String normalizedProjectId = requireProject(projectId);
        return jdbcTemplate.query(
                "SELECT project_id, dashboard_key, display_name::text, description, schema_version, " +
                        "definition::text, revision, is_default, is_active, created_at, updated_at " +
                        "FROM analytics_dashboards WHERE project_id = ? " +
                        "ORDER BY is_default DESC, updated_at DESC, dashboard_key ASC",
                this::mapRecord,
                normalizedProjectId
        );
    }

    public AdminDashboardRecord get(String projectId, String dashboardKey) {
        String normalizedProjectId = requireProject(projectId);
        String normalizedKey = normalizeDashboardKey(dashboardKey);
        return find(normalizedProjectId, normalizedKey).stream()
                .findFirst()
                .orElseThrow(AdminDashboardService::notFound);
    }

    @Transactional
    public AdminDashboardRecord upsert(
            String projectId,
            String dashboardKey,
            AdminDashboardUpsertRequest request
    ) {
        String normalizedProjectId = requireProject(projectId);
        String normalizedKey = normalizeDashboardKey(dashboardKey);
        validateRequest(request);
        definitionValidator.validateDisplayName(objectMapper.valueToTree(request.displayName()));
        JsonNode definition = objectMapper.valueToTree(request.definition());
        definitionValidator.validate(request.schemaVersion(), definition);

        lockProject(normalizedProjectId);
        // Dashboard 引用校验与指标停用必须持有同一项目级事务锁，避免并发写入失效引用。
        analyticsWriteLock.acquireProjectDefinitionWriteLock(normalizedProjectId);
        List<AdminDashboardRecord> existingRows = find(normalizedProjectId, normalizedKey);
        AdminDashboardRecord existing = existingRows.isEmpty() ? null : existingRows.getFirst();
        boolean active = request.isActive() == null
                ? existing == null || existing.isActive()
                : request.isActive();
        overviewMetricPolicy.validateForWrite(
                normalizedProjectId,
                definition,
                existing == null ? null : objectMapper.valueToTree(existing.definition())
        );
        counterPolicy.validateForWrite(
                normalizedProjectId,
                definition,
                existing == null ? null : objectMapper.valueToTree(existing.definition())
        );
        governedMetricPolicy.validateForWrite(
                normalizedProjectId,
                definition,
                existing == null ? null : objectMapper.valueToTree(existing.definition()),
                active
        );
        boolean defaultDashboard = request.isDefault() == null
                ? existing != null && existing.isDefault()
                : request.isDefault();
        if (defaultDashboard && !active) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_STATE",
                    "默认 dashboard 必须处于 active 状态"
            );
        }

        if (existing == null) {
            if (request.expectedRevision() != null && request.expectedRevision() != 0L) {
                throw revisionConflict();
            }
            if (defaultDashboard) {
                clearCurrentDefault(normalizedProjectId, null);
            }
            insert(normalizedProjectId, normalizedKey, request, defaultDashboard, active);
        } else {
            if (request.expectedRevision() == null) {
                throw new BusinessException(
                        "DASHBOARD_REVISION_REQUIRED",
                        "更新 dashboard 时必须提供 expectedRevision"
                );
            }
            if (request.expectedRevision() <= 0 || request.expectedRevision() != existing.revision()) {
                throw revisionConflict();
            }
            if (defaultDashboard) {
                clearCurrentDefault(normalizedProjectId, normalizedKey);
            }
            update(normalizedProjectId, normalizedKey, request, defaultDashboard, active);
        }
        return get(normalizedProjectId, normalizedKey);
    }

    @Transactional
    public void delete(String projectId, String dashboardKey, long expectedRevision) {
        String normalizedProjectId = requireProject(projectId);
        String normalizedKey = normalizeDashboardKey(dashboardKey);
        if (expectedRevision <= 0) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_REVISION",
                    "expectedRevision 必须大于 0"
            );
        }
        lockProject(normalizedProjectId);
        int deleted = jdbcTemplate.update(
                "DELETE FROM analytics_dashboards " +
                        "WHERE project_id = ? AND dashboard_key = ? AND revision = ?",
                normalizedProjectId,
                normalizedKey,
                expectedRevision
        );
        if (deleted == 0) {
            if (find(normalizedProjectId, normalizedKey).isEmpty()) {
                throw notFound();
            }
            throw revisionConflict();
        }
    }

    private void insert(
            String projectId,
            String dashboardKey,
            AdminDashboardUpsertRequest request,
            boolean defaultDashboard,
            boolean active
    ) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO analytics_dashboards " +
                            "(project_id, dashboard_key, display_name, description, schema_version, definition, " +
                            "revision, is_default, is_active) " +
                            "VALUES (?, ?, ?::jsonb, ?, ?, ?::jsonb, 1, ?, ?)",
                    projectId,
                    dashboardKey,
                    writeJson(request.displayName()),
                    normalizeDescription(request.description()),
                    request.schemaVersion(),
                    writeJson(request.definition()),
                    defaultDashboard,
                    active
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    "DASHBOARD_CONFLICT",
                    "dashboard key 或默认 dashboard 已存在",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void update(
            String projectId,
            String dashboardKey,
            AdminDashboardUpsertRequest request,
            boolean defaultDashboard,
            boolean active
    ) {
        int updated;
        try {
            updated = jdbcTemplate.update(
                    "UPDATE analytics_dashboards SET display_name = ?::jsonb, description = ?, " +
                            "schema_version = ?, definition = ?::jsonb, revision = revision + 1, " +
                            "is_default = ?, is_active = ? " +
                            "WHERE project_id = ? AND dashboard_key = ? AND revision = ?",
                    writeJson(request.displayName()),
                    normalizeDescription(request.description()),
                    request.schemaVersion(),
                    writeJson(request.definition()),
                    defaultDashboard,
                    active,
                    projectId,
                    dashboardKey,
                    request.expectedRevision()
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    "DASHBOARD_CONFLICT",
                    "默认 dashboard 冲突",
                    HttpStatus.CONFLICT
            );
        }
        if (updated != 1) {
            throw revisionConflict();
        }
    }

    private void clearCurrentDefault(String projectId, String exceptKey) {
        if (exceptKey == null) {
            jdbcTemplate.update(
                    "UPDATE analytics_dashboards SET is_default = FALSE, revision = revision + 1 " +
                            "WHERE project_id = ? AND is_default = TRUE",
                    projectId
            );
            return;
        }
        jdbcTemplate.update(
                "UPDATE analytics_dashboards SET is_default = FALSE, revision = revision + 1 " +
                        "WHERE project_id = ? AND dashboard_key <> ? AND is_default = TRUE",
                projectId,
                exceptKey
        );
    }

    private List<AdminDashboardRecord> find(String projectId, String dashboardKey) {
        return jdbcTemplate.query(
                "SELECT project_id, dashboard_key, display_name::text, description, schema_version, " +
                        "definition::text, revision, is_default, is_active, created_at, updated_at " +
                        "FROM analytics_dashboards WHERE project_id = ? AND dashboard_key = ?",
                this::mapRecord,
                projectId,
                dashboardKey
        );
    }

    private AdminDashboardRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new AdminDashboardRecord(
                resultSet.getString("project_id"),
                resultSet.getString("dashboard_key"),
                readDisplayName(resultSet.getString("display_name")),
                resultSet.getString("description"),
                resultSet.getInt("schema_version"),
                readDefinition(resultSet.getString("definition")),
                resultSet.getLong("revision"),
                resultSet.getBoolean("is_default"),
                resultSet.getBoolean("is_active"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private void lockProject(String projectId) {
        List<String> lockedProjects = jdbcTemplate.query(
                "SELECT project_id FROM analytics_projects WHERE project_id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getString("project_id"),
                projectId
        );
        if (lockedProjects.isEmpty()) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
        }
    }

    private static void validateRequest(AdminDashboardUpsertRequest request) {
        if (request == null) {
            throw new BusinessException("INVALID_DASHBOARD_REQUEST", "dashboard 请求不能为空");
        }
        if (request.displayName() == null || request.schemaVersion() == null || request.definition() == null) {
            throw new BusinessException("INVALID_DASHBOARD_REQUEST", "dashboard 必填字段不完整");
        }
        if (request.expectedRevision() != null && request.expectedRevision() < 0) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_REVISION",
                    "expectedRevision 不能小于 0"
            );
        }
    }

    private String requireProject(String rawProjectId) {
        String projectId = rawProjectId == null ? "" : rawProjectId.strip();
        if (!PROJECT_ID_PATTERN.matcher(projectId).matches()) {
            throw new BusinessException("INVALID_PROJECT", "projectId 格式无效");
        }
        AnalyticsProject project = projectMapper.selectOne(
                new LambdaQueryWrapper<AnalyticsProject>()
                        .eq(AnalyticsProject::getProjectId, projectId)
        );
        if (project == null) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
        }
        return projectId;
    }

    private static String normalizeDashboardKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.strip();
        if (!DASHBOARD_KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_KEY",
                    "dashboardKey 仅支持小写字母、数字、点、下划线和连字符"
            );
        }
        return key;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (description.length() > 1000) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_DESCRIPTION",
                    "description 长度不能超过 1000"
            );
        }
        String normalized = description.strip();
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_DEFINITION",
                    "dashboard JSON 无法序列化"
            );
        }
    }

    private Map<String, String> readDisplayName(String value) {
        try {
            return objectMapper.readValue(value, DISPLAY_NAME_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored dashboard displayName JSON is invalid", exception);
        }
    }

    private Map<String, Object> readDefinition(String value) {
        try {
            return objectMapper.readValue(value, DEFINITION_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored dashboard definition JSON is invalid", exception);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException("DASHBOARD_NOT_FOUND", "dashboard 不存在", HttpStatus.NOT_FOUND);
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "DASHBOARD_REVISION_CONFLICT",
                "dashboard 已被其他管理会话更新，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }
}
