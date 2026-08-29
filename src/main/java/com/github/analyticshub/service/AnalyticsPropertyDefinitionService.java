package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionsResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理项目级事件属性语义。
 *
 * <p>定义既服务于界面展示，也是交互式查询的安全 allowlist（允许清单）。
 * 未注册、敏感或未声明对应能力的属性不能进入筛选、分组和旅程计算。</p>
 */
@Service
public class AnalyticsPropertyDefinitionService {

    private static final Pattern PROJECT_ID = Pattern.compile("^[a-z0-9_-]{1,50}$");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$");
    private static final Pattern LOCALE_KEY = Pattern.compile("^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$");
    private static final TypeReference<LinkedHashMap<String, String>> DISPLAY_NAME_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisPackOwnershipService packOwnershipService;

    public AnalyticsPropertyDefinitionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AnalysisPackOwnershipService packOwnershipService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.packOwnershipService = packOwnershipService;
    }

    @Transactional(readOnly = true)
    public AnalyticsPropertyDefinitionsResponse list(String projectId) {
        String normalizedProjectId = requireProject(projectId);
        List<AnalyticsPropertyDefinitionResponse> items = jdbcTemplate.query("""
                SELECT property_key, display_name::text, data_type, description,
                       allowed_values::text, is_filterable, is_groupable, is_journey_key,
                       is_sensitive, is_active, created_at, updated_at
                  FROM analytics_property_definitions
                 WHERE project_id = ?
                 ORDER BY property_key
                """, (rs, rowNum) -> mapDefinition(normalizedProjectId, rs), normalizedProjectId);
        return new AnalyticsPropertyDefinitionsResponse(normalizedProjectId, List.copyOf(items));
    }

    @Transactional
    public AnalyticsPropertyDefinitionResponse upsert(
            String projectId,
            String propertyKey,
            AnalyticsPropertyDefinitionRequest request
    ) {
        String project = requireProject(projectId);
        String key = requirePropertyKey(propertyKey);
        packOwnershipService.acquireProjectDefinitionWriteLock(project);
        packOwnershipService.requirePropertyManuallyEditable(project, key);
        boolean governanceTransition = isLegacyUngovernedProject(project);
        AnalyticsPropertyDefinitionResponse saved = upsert(projectId, propertyKey, request, Set.of());
        packOwnershipService.requireTrustedSchemaPolicyCompatible(project, saved);
        if (governanceTransition) {
            requireCurrentGovernanceReferencesCovered(project);
        }
        return saved;
    }

    AnalyticsPropertyDefinitionResponse upsertFromPack(
            String projectId,
            String propertyKey,
            AnalyticsPropertyDefinitionRequest request,
            Set<String> targetPackMetricKeys
    ) {
        return upsert(
                projectId,
                propertyKey,
                request,
                targetPackMetricKeys == null ? Set.of() : targetPackMetricKeys
        );
    }

    private AnalyticsPropertyDefinitionResponse upsert(
            String projectId,
            String propertyKey,
            AnalyticsPropertyDefinitionRequest request,
            Set<String> excludedMetricKeys
    ) {
        String normalizedProjectId = requireProject(projectId);
        String normalizedKey = requirePropertyKey(propertyKey);
        ValidatedDefinition definition = validate(request);
        findDefinition(normalizedProjectId, normalizedKey).ifPresent(existing ->
                requireCapabilityReductionAllowed(
                        normalizedProjectId,
                        existing,
                        definition,
                        excludedMetricKeys
                )
        );
        jdbcTemplate.update("""
                INSERT INTO analytics_property_definitions
                    (project_id, property_key, display_name, data_type, description, allowed_values,
                     is_filterable, is_groupable, is_journey_key, is_sensitive, is_active)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, property_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    data_type = EXCLUDED.data_type,
                    description = EXCLUDED.description,
                    allowed_values = EXCLUDED.allowed_values,
                    is_filterable = EXCLUDED.is_filterable,
                    is_groupable = EXCLUDED.is_groupable,
                    is_journey_key = EXCLUDED.is_journey_key,
                    is_sensitive = EXCLUDED.is_sensitive,
                    is_active = EXCLUDED.is_active
                """,
                normalizedProjectId,
                normalizedKey,
                toJson(definition.displayName()),
                definition.dataType().name(),
                definition.description(),
                definition.allowedValues() == null ? null : toJson(definition.allowedValues()),
                definition.filterable(),
                definition.groupable(),
                definition.journeyKey(),
                definition.sensitive(),
                definition.active()
        );
        return requireDefinition(normalizedProjectId, normalizedKey);
    }

    void requireCanDeactivate(
            String projectId,
            Set<String> propertyKeys,
            Set<String> excludedMetricKeys
    ) {
        String normalizedProjectId = requireProject(projectId);
        for (String propertyKey : propertyKeys) {
            findDefinition(normalizedProjectId, requirePropertyKey(propertyKey)).ifPresent(existing ->
                    requireNoDependencies(
                            normalizedProjectId,
                            existing.propertyKey(),
                            true,
                            true,
                            true,
                            excludedMetricKeys == null ? Set.of() : excludedMetricKeys
                    )
            );
        }
    }

    boolean isLegacyUngovernedProject(String projectId) {
        String project = requireProject(projectId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_property_definitions WHERE project_id = ?",
                Integer.class,
                project
        );
        return count == null || count == 0;
    }

    /** 首次启用属性治理时，旧配置引用必须已在新字典中完整声明，避免升级后运行时才失效。 */
    void requireCurrentGovernanceReferencesCovered(String projectId) {
        String project = requireProject(projectId);
        Map<String, AnalyticsPropertyDefinitionResponse> definitions = new LinkedHashMap<>();
        for (AnalyticsPropertyDefinitionResponse definition : list(project).items()) {
            definitions.put(definition.propertyKey(), definition);
        }
        if (definitions.isEmpty()) return;

        Set<String> blockers = new LinkedHashSet<>();
        jdbcTemplate.query("""
                SELECT metric_key, definition::text
                  FROM analytics_metric_definitions
                 WHERE project_id = ? AND is_active = TRUE
                """, (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> collectGovernanceBlockers(
                blockers,
                definitions,
                "metric " + resultSet.getString("metric_key"),
                objectMapper.readTree(resultSet.getString("definition"))
        ), project);
        jdbcTemplate.query("""
                SELECT dashboard_key, definition::text
                  FROM analytics_dashboards
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            JsonNode dashboard = objectMapper.readTree(resultSet.getString("definition"));
            for (JsonNode widget : dashboard.path("widgets")) {
                if (!"core.productFunnel".equals(textOrNull(widget.get("type")))) continue;
                collectGovernanceBlockers(
                        blockers,
                        definitions,
                        "dashboard " + resultSet.getString("dashboard_key"),
                        widget.path("config")
                );
            }
        }, project);
        if (!blockers.isEmpty()) {
            throw new BusinessException(
                    "ANALYTICS_GOVERNANCE_TRANSITION_BLOCKED",
                    "首次启用属性治理前需补齐已有配置引用: " + String.join("; ", blockers)
            );
        }
    }

    private static void collectGovernanceBlockers(
            Set<String> blockers,
            Map<String, AnalyticsPropertyDefinitionResponse> definitions,
            String owner,
            JsonNode config
    ) {
        String groupBy = textOrNull(config.get("groupBy"));
        if (groupBy != null && !hasCapability(definitions.get(groupBy), false)) {
            blockers.add(owner + " groupBy=" + groupBy);
        }
        String journeyKey = textOrNull(config.get("journeyKey"));
        if (journeyKey != null && !hasCapability(definitions.get(journeyKey), true)) {
            blockers.add(owner + " journeyKey=" + journeyKey);
        }
        String propertyKey = textOrNull(config.get("propertyKey"));
        if (propertyKey != null && !hasNumericSummaryCapability(definitions.get(propertyKey))) {
            blockers.add(owner + " propertyKey=" + propertyKey);
        }
    }

    private static boolean hasCapability(
            AnalyticsPropertyDefinitionResponse definition,
            boolean journeyKey
    ) {
        return definition != null && definition.active() && !definition.sensitive()
                && (journeyKey ? definition.journeyKey() : definition.groupable());
    }

    private static boolean hasNumericSummaryCapability(
            AnalyticsPropertyDefinitionResponse definition
    ) {
        return definition != null && definition.active() && !definition.sensitive()
                && definition.filterable()
                && (definition.dataType() == com.github.analyticshub.dto.AnalyticsPropertyDataType.INTEGER
                    || definition.dataType() == com.github.analyticshub.dto.AnalyticsPropertyDataType.NUMBER);
    }

    @Transactional(readOnly = true)
    public Map<String, AnalyticsPropertyDefinitionResponse> requireCapabilities(
            String projectId,
            List<String> propertyKeys
    ) {
        String normalizedProjectId = requireProject(projectId);
        if (propertyKeys == null || propertyKeys.isEmpty()) {
            return Map.of();
        }
        List<String> keys = propertyKeys.stream().map(AnalyticsPropertyDefinitionService::requirePropertyKey)
                .distinct().toList();
        String placeholders = String.join(",", keys.stream().map(ignored -> "?").toList());
        List<Object> args = new java.util.ArrayList<>();
        args.add(normalizedProjectId);
        args.addAll(keys);
        Map<String, AnalyticsPropertyDefinitionResponse> definitions = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT property_key, display_name::text, data_type, description,
                       allowed_values::text, is_filterable, is_groupable, is_journey_key,
                       is_sensitive, is_active, created_at, updated_at
                  FROM analytics_property_definitions
                 WHERE project_id = ? AND property_key IN (%s)
                """.formatted(placeholders), rs -> {
            AnalyticsPropertyDefinitionResponse item = mapDefinition(normalizedProjectId, rs);
            definitions.put(item.propertyKey(), item);
        }, args.toArray());
        if (definitions.size() != keys.size()) {
            throw invalid("存在未注册的事件属性；请先在属性语义中定义后再分析");
        }
        return Map.copyOf(definitions);
    }

    private AnalyticsPropertyDefinitionResponse requireDefinition(String projectId, String propertyKey) {
        return findDefinition(projectId, propertyKey).orElseThrow(() ->
                new BusinessException("ANALYTICS_PROPERTY_NOT_FOUND", "事件属性定义不存在", HttpStatus.NOT_FOUND)
        );
    }

    private java.util.Optional<AnalyticsPropertyDefinitionResponse> findDefinition(
            String projectId,
            String propertyKey
    ) {
        List<AnalyticsPropertyDefinitionResponse> rows = jdbcTemplate.query("""
                SELECT property_key, display_name::text, data_type, description,
                       allowed_values::text, is_filterable, is_groupable, is_journey_key,
                       is_sensitive, is_active, created_at, updated_at
                  FROM analytics_property_definitions
                 WHERE project_id = ? AND property_key = ?
                """, (rs, rowNum) -> mapDefinition(projectId, rs), projectId, propertyKey);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.getFirst());
    }

    private void requireCapabilityReductionAllowed(
            String projectId,
            AnalyticsPropertyDefinitionResponse existing,
            ValidatedDefinition next,
            Set<String> excludedMetricKeys
    ) {
        boolean disabling = existing.active() && !next.active();
        boolean changesType = existing.dataType() != next.dataType();
        boolean narrowsAllowedValues = narrowsAllowedValues(existing.allowedValues(), next.allowedValues());
        boolean removesFilter = disabling || (existing.filterable() && !next.filterable());
        boolean removesGroup = disabling || (existing.groupable() && !next.groupable());
        boolean removesJourney = disabling || (existing.journeyKey() && !next.journeyKey());
        if (removesFilter || removesGroup || removesJourney || changesType || narrowsAllowedValues) {
            requireNoDependencies(
                    projectId,
                    existing.propertyKey(),
                    removesFilter || changesType || narrowsAllowedValues,
                    removesGroup || changesType,
                    removesJourney || changesType,
                    excludedMetricKeys
            );
        }
    }

    private static boolean narrowsAllowedValues(List<String> current, List<String> next) {
        if (next == null) return false;
        if (current == null) return true;
        return !next.containsAll(current);
    }

    private void requireNoDependencies(
            String projectId,
            String propertyKey,
            boolean checkFilter,
            boolean checkGroup,
            boolean checkJourney,
            Set<String> excludedMetricKeys
    ) {
        jdbcTemplate.query("""
                SELECT metric_key, definition::text
                  FROM analytics_metric_definitions
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            String metricKey = resultSet.getString("metric_key");
            if (excludedMetricKeys.contains(metricKey)) return;
            JsonNode definition = objectMapper.readTree(resultSet.getString("definition"));
            if ((checkFilter && (filterReferences(definition, propertyKey)
                    || propertyKey.equals(textOrNull(definition.get("propertyKey")))))
                    || (checkGroup && propertyKey.equals(textOrNull(definition.get("groupBy"))))
                    || (checkJourney && propertyKey.equals(textOrNull(definition.get("journeyKey"))))) {
                throw invalid("属性 " + propertyKey + " 仍被指标 " + metricKey + " 使用，不能停用对应能力");
            }
        }, projectId);

        if (!checkGroup && !checkJourney) return;
        jdbcTemplate.query("""
                SELECT dashboard_key, definition::text
                  FROM analytics_dashboards
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            JsonNode definition = objectMapper.readTree(resultSet.getString("definition"));
            for (JsonNode widget : definition.path("widgets")) {
                if (!"core.productFunnel".equals(textOrNull(widget.get("type")))) continue;
                JsonNode config = widget.path("config");
                if ((checkGroup && propertyKey.equals(textOrNull(config.get("groupBy"))))
                        || (checkJourney && propertyKey.equals(textOrNull(config.get("journeyKey"))))) {
                    throw invalid("属性 " + propertyKey + " 仍被 Dashboard "
                            + resultSet.getString("dashboard_key") + " 使用，不能停用对应能力");
                }
            }
        }, projectId);
    }

    private static boolean filterReferences(JsonNode definition, String propertyKey) {
        JsonNode filters = definition.path("propertyFilters");
        if (!filters.isArray()) return false;
        for (JsonNode filter : filters) {
            if (propertyKey.equals(textOrNull(filter.get("propertyKey")))) return true;
        }
        return false;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private AnalyticsPropertyDefinitionResponse mapDefinition(
            String projectId,
            java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        return new AnalyticsPropertyDefinitionResponse(
                projectId,
                rs.getString("property_key"),
                readJson(rs.getString("display_name"), DISPLAY_NAME_TYPE),
                AnalyticsPropertyDataType.valueOf(rs.getString("data_type")),
                rs.getString("description"),
                readJson(rs.getString("allowed_values"), STRING_LIST_TYPE),
                rs.getBoolean("is_filterable"),
                rs.getBoolean("is_groupable"),
                rs.getBoolean("is_journey_key"),
                rs.getBoolean("is_sensitive"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private String requireProject(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!PROJECT_ID.matcher(normalized).matches()) {
            throw BusinessException.invalidProject(normalized);
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_projects WHERE project_id = ?",
                Integer.class,
                normalized
        );
        if (count == null || count == 0) {
            throw BusinessException.invalidProject(normalized);
        }
        return normalized;
    }

    private static String requirePropertyKey(String propertyKey) {
        String normalized = propertyKey == null ? "" : propertyKey.strip();
        if (!PROPERTY_KEY.matcher(normalized).matches()) {
            throw invalid("propertyKey 格式无效");
        }
        return normalized;
    }

    private static ValidatedDefinition validate(AnalyticsPropertyDefinitionRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isEmpty()
                || request.displayName().size() > 16
                || request.dataType() == null || request.filterable() == null
                || request.groupable() == null || request.journeyKey() == null
                || request.sensitive() == null || request.active() == null) {
            throw invalid("属性定义不完整");
        }
        Map<String, String> displayName = new LinkedHashMap<>();
        request.displayName().forEach((locale, name) -> {
            String normalizedLocale = locale == null ? "" : locale.strip();
            String normalizedName = name == null ? "" : name.strip();
            if (!LOCALE_KEY.matcher(normalizedLocale).matches()
                    || normalizedName.isEmpty() || normalizedName.length() > 200) {
                throw invalid("displayName 的地区或名称格式无效");
            }
            if (displayName.putIfAbsent(normalizedLocale, normalizedName) != null) {
                throw invalid("displayName 不能包含重复地区");
            }
        });
        if (request.description() != null && request.description().length() > 1000) {
            throw invalid("description 不能超过 1000 个字符");
        }
        if (Boolean.TRUE.equals(request.sensitive())
                && (Boolean.TRUE.equals(request.filterable())
                || Boolean.TRUE.equals(request.groupable())
                || Boolean.TRUE.equals(request.journeyKey()))) {
            throw invalid("敏感属性不能用于筛选、分组或旅程关联");
        }
        if (Boolean.TRUE.equals(request.journeyKey())
                && request.dataType() != AnalyticsPropertyDataType.STRING) {
            throw invalid("journeyKey 仅支持 STRING 属性");
        }
        List<String> allowed = null;
        if (request.allowedValues() != null) {
            if (request.allowedValues().size() > 100) {
                throw invalid("allowedValues 不能超过 100 项");
            }
            List<String> normalizedValues = new java.util.ArrayList<>();
            for (String value : request.allowedValues()) {
                String normalized;
                try {
                    normalized = AnalyticsPropertyValueNormalizer.normalize(value, request.dataType());
                } catch (IllegalArgumentException exception) {
                    throw invalid("allowedValues 包含无效值：" + exception.getMessage());
                }
                if (normalizedValues.contains(normalized)) {
                    throw invalid("allowedValues 规范化后不能包含重复值");
                }
                normalizedValues.add(normalized);
            }
            allowed = List.copyOf(normalizedValues);
        }
        return new ValidatedDefinition(
                Map.copyOf(displayName), request.dataType(), request.description(), allowed,
                request.filterable(), request.groupable(), request.journeyKey(), request.sensitive(), request.active()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize analytics property definition", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored analytics property definition is invalid", exception);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_ANALYTICS_PROPERTY", message);
    }

    private record ValidatedDefinition(
            Map<String, String> displayName,
            AnalyticsPropertyDataType dataType,
            String description,
            List<String> allowedValues,
            boolean filterable,
            boolean groupable,
            boolean journeyKey,
            boolean sensitive,
            boolean active
    ) {}
}
