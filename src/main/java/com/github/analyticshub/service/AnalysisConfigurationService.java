package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalysisPackImportRequest;
import com.github.analyticshub.dto.AnalysisPackDetailResponse;
import com.github.analyticshub.dto.AnalysisPackVersionSnapshot;
import com.github.analyticshub.dto.AnalysisPackResponse;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricType;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.TrustedSchemaPolicyResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理可移植的指标定义与项目 Analysis Pack。
 *
 * <p>Pack 只承载声明式 JSON，不能包含 SQL、脚本、URL 或远程加载指令。
 * 导入在系统库事务内同时更新属性、指标、pack 快照和审计记录。</p>
 */
@Service
public class AnalysisConfigurationService {

    private static final Pattern PROJECT_ID = Pattern.compile("^[a-z0-9_-]{1,50}$");
    private static final Pattern PACK_KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern METRIC_KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "trustedSchemaPolicy", "properties", "metrics"
    );
    private static final Set<String> TRUSTED_SCHEMA_POLICY_FIELDS = Set.of("propertyKey", "trustedValues");
    private static final Set<String> PROPERTY_FIELDS = Set.of(
            "propertyKey", "displayName", "dataType", "description", "allowedValues",
            "filterable", "groupable", "journeyKey", "sensitive", "active"
    );
    private static final Set<String> METRIC_FIELDS = Set.of(
            "metricKey", "displayName", "metricType", "definition", "description", "active"
    );
    private static final TypeReference<LinkedHashMap<String, String>> DISPLAY_NAME_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsPropertyDefinitionService propertyDefinitionService;
    private final AnalyticsPropertyFilterService propertyFilterService;
    private final SemanticDictionaryService semanticDictionaryService;
    private final AnalysisPackOwnershipService packOwnershipService;

    public AnalysisConfigurationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AnalyticsPropertyDefinitionService propertyDefinitionService,
            AnalyticsPropertyFilterService propertyFilterService,
            SemanticDictionaryService semanticDictionaryService,
            AnalysisPackOwnershipService packOwnershipService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.propertyDefinitionService = propertyDefinitionService;
        this.propertyFilterService = propertyFilterService;
        this.semanticDictionaryService = semanticDictionaryService;
        this.packOwnershipService = packOwnershipService;
    }

    @Transactional(readOnly = true)
    public List<AnalyticsMetricDefinitionResponse> listMetrics(String projectId) {
        String project = requireProject(projectId);
        return jdbcTemplate.query("""
                SELECT metric_key, display_name::text, metric_type, definition::text,
                       description, is_active, created_at, updated_at
                  FROM analytics_metric_definitions
                 WHERE project_id = ?
                 ORDER BY metric_key
                """, (rs, rowNum) -> new AnalyticsMetricDefinitionResponse(
                project,
                rs.getString("metric_key"),
                readJson(rs.getString("display_name"), DISPLAY_NAME_TYPE),
                AnalyticsMetricType.valueOf(rs.getString("metric_type")),
                objectMapper.readTree(rs.getString("definition")),
                rs.getString("description"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        ), project);
    }

    private List<AnalysisPackVersionSnapshot> listPackVersions(String projectId, String packKey) {
        return jdbcTemplate.query("""
                SELECT pack_version, display_name::text, manifest::text, checksum_sha256,
                       operation, applied_at
                  FROM analytics_analysis_pack_audits
                 WHERE project_id = ? AND pack_key = ?
                 ORDER BY pack_version DESC, id DESC
                """, (rs, rowNum) -> new AnalysisPackVersionSnapshot(
                rs.getInt("pack_version"),
                readJson(rs.getString("display_name"), DISPLAY_NAME_TYPE),
                objectMapper.readTree(rs.getString("manifest")),
                rs.getString("checksum_sha256"),
                rs.getString("operation"),
                rs.getObject("applied_at", OffsetDateTime.class).toInstant()
        ), projectId, packKey);
    }

    @Transactional(readOnly = true)
    public List<AnalysisPackDetailResponse> listAnalysisPacks(String projectId) {
        String project = requireProject(projectId);
        List<AnalysisPackDetailResponse> packs = jdbcTemplate.query("""
                SELECT pack_key, pack_version, display_name::text, manifest::text,
                       checksum_sha256, is_active, created_at, updated_at
                  FROM analytics_analysis_packs
                 WHERE project_id = ?
                 ORDER BY pack_key
                """, (rs, rowNum) -> new AnalysisPackDetailResponse(
                project,
                rs.getString("pack_key"),
                rs.getInt("pack_version"),
                readJson(rs.getString("display_name"), DISPLAY_NAME_TYPE),
                objectMapper.readTree(rs.getString("manifest")),
                rs.getString("checksum_sha256"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                List.of()
        ), project);
        return packs.stream().map(pack -> new AnalysisPackDetailResponse(
                pack.projectId(), pack.packKey(), pack.packVersion(), pack.displayName(), pack.manifest(),
                pack.checksumSha256(), pack.active(), pack.createdAt(), pack.updatedAt(),
                listPackVersions(project, pack.packKey())
        )).toList();
    }

    @Transactional(readOnly = true)
    public AnalyticsMetricDefinitionResponse getMetric(String projectId, String metricKey) {
        String project = requireProject(projectId);
        String key = requireMetricKey(metricKey);
        return listMetrics(project).stream().filter(item -> item.metricKey().equals(key)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ANALYTICS_METRIC_NOT_FOUND", "指标定义不存在: " + key,
                        org.springframework.http.HttpStatus.NOT_FOUND
                ));
    }

    @Transactional(readOnly = true)
    public TrustedSchemaPolicyResponse getTrustedSchemaPolicy(String projectId) {
        String project = requireProject(projectId);
        TrustedSchemaPolicy policy = loadTrustedSchemaPolicy(project, null);
        return policy == null ? null : new TrustedSchemaPolicyResponse(
                project, policy.propertyKey(), List.copyOf(policy.trustedValues())
        );
    }

    @Transactional
    public AnalyticsMetricDefinitionResponse upsertMetric(
            String projectId,
            String metricKey,
            AnalyticsMetricDefinitionRequest request
    ) {
        String project = requireProject(projectId);
        String key = requireMetricKey(metricKey);
        packOwnershipService.acquireProjectDefinitionWriteLock(project);
        packOwnershipService.requireMetricManuallyEditable(project, key);
        return upsertMetricFromPack(project, key, request, loadTrustedSchemaPolicy(project, null));
    }

    private AnalyticsMetricDefinitionResponse upsertMetricFromPack(
            String project,
            String key,
            AnalyticsMetricDefinitionRequest request,
            TrustedSchemaPolicy trustedSchemaPolicy
    ) {
        validateMetricRequest(project, request, trustedSchemaPolicy);
        jdbcTemplate.update("""
                INSERT INTO analytics_metric_definitions
                    (project_id, metric_key, display_name, metric_type, definition, description, is_active)
                VALUES (?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (project_id, metric_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    metric_type = EXCLUDED.metric_type,
                    definition = EXCLUDED.definition,
                    description = EXCLUDED.description,
                    is_active = EXCLUDED.is_active
                """,
                project, key, toJson(request.displayName()), request.metricType().name(),
                request.definition().toString(), request.description(), request.active());
        return listMetrics(project).stream().filter(item -> item.metricKey().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Metric definition was not persisted"));
    }

    @Transactional
    public AnalysisPackResponse importPack(
            String projectId,
            String packKey,
            AnalysisPackImportRequest request
    ) {
        String project = requireProject(projectId);
        String key = requirePackKey(packKey);
        ValidatedPack pack = validatePack(request);
        String manifestJson = request.manifest().toString();
        String checksum = sha256(manifestJson);
        packOwnershipService.acquireProjectDefinitionWriteLock(project);
        boolean governanceTransition = propertyDefinitionService.isLegacyUngovernedProject(project);
        ExistingPack existingPack = loadExistingPackForUpdate(project, key);
        validatePackVersion(existingPack, request.packVersion(), checksum, request.displayName());
        if (existingPack != null && request.packVersion() == existingPack.version()) {
            return new AnalysisPackResponse(
                    project, key, existingPack.version(), existingPack.displayName(),
                    existingPack.checksum(), true,
                    existingPack.manifest().path("properties").size(),
                    existingPack.manifest().path("metrics").size(),
                    existingPack.updatedAt().toInstant()
            );
        }

        Set<String> propertyKeys = packKeys(pack.properties(), "propertyKey");
        Set<String> metricKeys = packKeys(pack.metrics(), "metricKey");
        if (existingPack == null && propertyKeys.isEmpty() && metricKeys.isEmpty()) {
            throw invalid("首次导入的 Analysis Pack 至少需要包含一个 property 或 metric");
        }
        requireDeactivationConfirmation(
                existingPack,
                propertyKeys,
                metricKeys,
                pack.trustedSchemaPolicy(),
                request.confirmDeactivations()
        );
        TrustedSchemaPolicy otherPackPolicy = loadTrustedSchemaPolicy(project, key);
        if (pack.trustedSchemaPolicy() != null && otherPackPolicy != null) {
            throw invalid("同一项目只能由一个 active Analysis Pack 声明 trustedSchemaPolicy");
        }
        TrustedSchemaPolicy effectivePolicy = pack.trustedSchemaPolicy() != null
                ? pack.trustedSchemaPolicy()
                : otherPackPolicy;
        Set<String> currentPackMetricKeys = currentPackMetricKeys(existingPack, metricKeys);
        rejectKeysOwnedByAnotherPack(project, key, propertyKeys, metricKeys);

        int propertiesApplied = 0;
        for (JsonNode property : pack.properties()) {
            String propertyKey = requiredText(property, "propertyKey", 80);
            tools.jackson.databind.node.ObjectNode propertyPayload =
                    (tools.jackson.databind.node.ObjectNode) property.deepCopy();
            propertyPayload.remove("propertyKey");
            try {
                AnalyticsPropertyDefinitionRequest definition = objectMapper.treeToValue(
                        propertyPayload, AnalyticsPropertyDefinitionRequest.class
                );
                propertyDefinitionService.upsertFromPack(
                        project, propertyKey, definition, currentPackMetricKeys
                );
            } catch (Exception exception) {
                throw invalidPackItem("properties", propertyKey, exception);
            }
            propertiesApplied++;
        }
        // 先停用目标快照已删除的属性，让后续 metric 校验只看到最终可用能力。
        // 任一 metric 仍引用被删除属性时会失败，并由外层事务回滚整个 Pack 导入。
        deactivateRemovedProperties(project, existingPack, propertyKeys, currentPackMetricKeys);
        if (governanceTransition && !propertyDefinitionService.isLegacyUngovernedProject(project)) {
            propertyDefinitionService.requireCurrentGovernanceReferencesCovered(project);
        }
        validateTrustedSchemaProperty(project, effectivePolicy);
        int metricsApplied = 0;
        for (JsonNode metric : pack.metrics()) {
            String metricKey = requiredText(metric, "metricKey", 100);
            tools.jackson.databind.node.ObjectNode metricPayload =
                    (tools.jackson.databind.node.ObjectNode) metric.deepCopy();
            metricPayload.remove("metricKey");
            try {
                AnalyticsMetricDefinitionRequest definition = objectMapper.treeToValue(
                        metricPayload, AnalyticsMetricDefinitionRequest.class
                );
                upsertMetricFromPack(project, metricKey, definition, effectivePolicy);
            } catch (Exception exception) {
                throw invalidPackItem("metrics", metricKey, exception);
            }
            metricsApplied++;
        }

        deactivateRemovedMetrics(project, existingPack, metricKeys);
        validateActiveMetricsAgainstPolicy(project, effectivePolicy);
        jdbcTemplate.update("""
                INSERT INTO analytics_analysis_packs
                    (project_id, pack_key, pack_version, display_name, manifest, checksum_sha256, is_active)
                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, TRUE)
                ON CONFLICT (project_id, pack_key) DO UPDATE SET
                    pack_version = EXCLUDED.pack_version,
                    display_name = EXCLUDED.display_name,
                    manifest = EXCLUDED.manifest,
                    checksum_sha256 = EXCLUDED.checksum_sha256,
                    is_active = TRUE
                """, project, key, request.packVersion(), toJson(request.displayName()), manifestJson, checksum);
        jdbcTemplate.update("""
                INSERT INTO analytics_analysis_pack_audits
                    (project_id, pack_key, pack_version, display_name, manifest, checksum_sha256, operation)
                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                ON CONFLICT (project_id, pack_key, pack_version) DO NOTHING
                """, project, key, request.packVersion(), toJson(request.displayName()), manifestJson, checksum,
                existingPack == null ? "IMPORT" : "UPDATE");
        OffsetDateTime updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM analytics_analysis_packs WHERE project_id = ? AND pack_key = ?",
                OffsetDateTime.class,
                project,
                key
        );
        return new AnalysisPackResponse(
                project, key, request.packVersion(), Map.copyOf(request.displayName()), checksum, true,
                propertiesApplied, metricsApplied, updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private ExistingPack loadExistingPackForUpdate(String projectId, String packKey) {
        List<ExistingPack> rows = jdbcTemplate.query("""
                SELECT pack_version, checksum_sha256, display_name::text, manifest::text, updated_at
                  FROM analytics_analysis_packs
                 WHERE project_id = ? AND pack_key = ?
                 FOR UPDATE
                """, (resultSet, rowNumber) -> new ExistingPack(
                resultSet.getInt("pack_version"),
                resultSet.getString("checksum_sha256"),
                readJson(resultSet.getString("display_name"), DISPLAY_NAME_TYPE),
                objectMapper.readTree(resultSet.getString("manifest")),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        ), projectId, packKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validatePackVersion(
            ExistingPack existing,
            int nextVersion,
            String nextChecksum,
            Map<String, String> nextDisplayName
    ) {
        if (existing == null) return;
        if (nextVersion < existing.version()) {
            throw invalid("Analysis Pack 版本不能回退");
        }
        if (nextVersion == existing.version() && !nextChecksum.equals(existing.checksum())) {
            throw invalid("相同 Analysis Pack 版本不能对应不同内容");
        }
        if (nextVersion == existing.version() && !existing.displayName().equals(nextDisplayName)) {
            throw invalid("相同 Analysis Pack 版本不能对应不同展示名称");
        }
    }

    private void rejectKeysOwnedByAnotherPack(
            String projectId,
            String packKey,
            Set<String> propertyKeys,
            Set<String> metricKeys
    ) {
        jdbcTemplate.query("""
                SELECT pack_key, manifest::text
                  FROM analytics_analysis_packs
                 WHERE project_id = ? AND pack_key <> ? AND is_active = TRUE
                """, resultSet -> {
            JsonNode manifest = objectMapper.readTree(resultSet.getString("manifest"));
            Set<String> otherProperties = packKeys(iterable(manifest.path("properties")), "propertyKey");
            Set<String> otherMetrics = packKeys(iterable(manifest.path("metrics")), "metricKey");
            Set<String> propertyOverlap = intersection(propertyKeys, otherProperties);
            Set<String> metricOverlap = intersection(metricKeys, otherMetrics);
            if (!propertyOverlap.isEmpty() || !metricOverlap.isEmpty()) {
                throw invalid("Analysis Pack 与 " + resultSet.getString("pack_key")
                        + " 存在重复定义；property=" + propertyOverlap + ", metric=" + metricOverlap);
            }
        }, projectId, packKey);
    }

    private void deactivateRemovedProperties(
            String projectId,
            ExistingPack existing,
            Set<String> propertyKeys,
            Set<String> currentPackMetricKeys
    ) {
        if (existing == null) return;
        Set<String> removedProperties = packKeys(
                iterable(existing.manifest().path("properties")), "propertyKey"
        );
        removedProperties.removeAll(propertyKeys);
        propertyDefinitionService.requireCanDeactivate(
                projectId, removedProperties, currentPackMetricKeys
        );
        for (String propertyKey : removedProperties) {
            jdbcTemplate.update("""
                    UPDATE analytics_property_definitions
                       SET is_active = FALSE
                     WHERE project_id = ? AND property_key = ?
                    """, projectId, propertyKey);
        }
    }

    private static Set<String> currentPackMetricKeys(ExistingPack existing, Set<String> targetMetricKeys) {
        Set<String> keys = existing == null
                ? new LinkedHashSet<>()
                : packKeys(iterable(existing.manifest().path("metrics")), "metricKey");
        keys.addAll(targetMetricKeys);
        return keys;
    }

    private void deactivateRemovedMetrics(
            String projectId,
            ExistingPack existing,
            Set<String> metricKeys
    ) {
        if (existing == null) return;
        Set<String> removedMetrics = packKeys(
                iterable(existing.manifest().path("metrics")), "metricKey"
        );
        removedMetrics.removeAll(metricKeys);
        for (String metricKey : removedMetrics) {
            jdbcTemplate.update("""
                    UPDATE analytics_metric_definitions
                       SET is_active = FALSE
                     WHERE project_id = ? AND metric_key = ?
                    """, projectId, metricKey);
        }
    }

    private static Set<String> packKeys(Iterable<JsonNode> items, String field) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String key = requiredText(item, field, "propertyKey".equals(field) ? 80 : 100);
            if (!keys.add(key)) {
                throw invalid("Analysis Pack 不能包含重复的 " + field + ": " + key);
            }
        }
        return keys;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private void requireDeactivationConfirmation(
            ExistingPack existing,
            Set<String> nextPropertyKeys,
            Set<String> nextMetricKeys,
            TrustedSchemaPolicy nextPolicy,
            Boolean confirmed
    ) {
        if (existing == null) return;
        Set<String> removedProperties = packKeys(
                iterable(existing.manifest().path("properties")), "propertyKey"
        );
        Set<String> removedMetrics = packKeys(
                iterable(existing.manifest().path("metrics")), "metricKey"
        );
        removedProperties.removeAll(nextPropertyKeys);
        removedMetrics.removeAll(nextMetricKeys);
        boolean removesTrustedPolicy = parseTrustedSchemaPolicy(
                existing.manifest().get("trustedSchemaPolicy")
        ) != null && nextPolicy == null;
        if ((!removedProperties.isEmpty() || !removedMetrics.isEmpty() || removesTrustedPolicy)
                && !Boolean.TRUE.equals(confirmed)) {
            throw new BusinessException(
                    "ANALYSIS_PACK_DEACTIVATION_CONFIRMATION_REQUIRED",
                    "Analysis Pack 是完整快照；本次更新会停用已省略的配置，请显式确认后重试",
                    org.springframework.http.HttpStatus.CONFLICT,
                    Map.of(
                            "removedPropertyKeys", List.copyOf(removedProperties),
                            "removedMetricKeys", List.copyOf(removedMetrics),
                            "removesTrustedSchemaPolicy", removesTrustedPolicy
                    )
            );
        }
    }

    private TrustedSchemaPolicy loadTrustedSchemaPolicy(String projectId, String excludedPackKey) {
        String sql = """
                SELECT pack_key, manifest::text
                  FROM analytics_analysis_packs
                 WHERE project_id = ? AND is_active = TRUE
                """ + (excludedPackKey == null ? "" : " AND pack_key <> ?");
        Object[] arguments = excludedPackKey == null
                ? new Object[] {projectId}
                : new Object[] {projectId, excludedPackKey};
        List<TrustedSchemaPolicy> policies = jdbcTemplate.query(sql, (rs, rowNum) -> {
            JsonNode manifest = objectMapper.readTree(rs.getString("manifest"));
            return parseTrustedSchemaPolicy(manifest.get("trustedSchemaPolicy"));
        }, arguments).stream().filter(java.util.Objects::nonNull).toList();
        if (policies.size() > 1) {
            throw invalid("同一项目存在多个 active trustedSchemaPolicy，请先收口 Analysis Pack");
        }
        return policies.isEmpty() ? null : policies.getFirst();
    }

    private TrustedSchemaPolicy parseTrustedSchemaPolicy(JsonNode node) {
        if (node == null || node.isNull()) return null;
        requireOnlyFields(node, TRUSTED_SCHEMA_POLICY_FIELDS, "trustedSchemaPolicy");
        String propertyKey = requiredText(node, "propertyKey", 80);
        JsonNode valuesNode = node.get("trustedValues");
        if (valuesNode == null || !valuesNode.isArray() || valuesNode.isEmpty() || valuesNode.size() > 20) {
            throw invalid("trustedSchemaPolicy.trustedValues 必须包含 1 到 20 个字符串");
        }
        LinkedHashSet<String> trustedValues = new LinkedHashSet<>();
        valuesNode.forEach(value -> {
            if (!value.isString()) throw invalid("trustedSchemaPolicy.trustedValues 只接受字符串");
            trustedValues.add(AnalyticsPropertyValueNormalizer.normalize(
                    value.asString(), AnalyticsPropertyDataType.STRING
            ));
        });
        if (trustedValues.size() != valuesNode.size()) {
            throw invalid("trustedSchemaPolicy.trustedValues 规范化后不能重复");
        }
        return new TrustedSchemaPolicy(propertyKey, List.copyOf(trustedValues));
    }

    private void validateTrustedSchemaProperty(String projectId, TrustedSchemaPolicy policy) {
        if (policy == null) return;
        AnalyticsPropertyDefinitionResponse definition = propertyDefinitionService
                .requireCapabilities(projectId, List.of(policy.propertyKey()))
                .get(policy.propertyKey());
        if (definition == null || !definition.active() || definition.sensitive()
                || !definition.filterable() || definition.dataType() != AnalyticsPropertyDataType.STRING
                || definition.allowedValues() == null
                || !definition.allowedValues().containsAll(policy.trustedValues())) {
            throw invalid("trustedSchemaPolicy 必须引用 active、非敏感、可筛选的 STRING 属性，且可信值属于 allowedValues");
        }
    }

    private void validateMetricSchemaScope(Boolean active, JsonNode definition, TrustedSchemaPolicy policy) {
        if (!Boolean.TRUE.equals(active)) return;
        JsonNode scopeNode = definition.get("schemaScope");
        if (scopeNode != null && !scopeNode.isNull()) {
            String scope = requiredText(definition, "schemaScope", 40);
            if (!"CROSS_VERSION_VERIFIED".equals(scope)) {
                throw invalid("definition.schemaScope 只支持 CROSS_VERSION_VERIFIED");
            }
            String reason = requiredText(definition, "schemaScopeReason", 500);
            if (reason.strip().length() < 10) {
                throw invalid("跨版本指标必须提供至少 10 个字符的 schemaScopeReason");
            }
            return;
        }
        if (definition.has("schemaScopeReason")) {
            throw invalid("schemaScopeReason 只能与 schemaScope 一起使用");
        }
        if (policy == null) return;
        JsonNode filters = definition.get("propertyFilters");
        if (filters == null || !filters.isArray()) {
            throw invalid("受治理指标必须显式筛选 trustedSchemaPolicy，或声明已验证的跨版本口径");
        }
        for (JsonNode filter : filters) {
            if (!policy.propertyKey().equals(filter.path("propertyKey").asString())) continue;
            String operator = filter.path("operator").asString();
            JsonNode values = filter.get("values");
            if (!("EQ".equals(operator) || "IN".equals(operator))
                    || values == null || !values.isArray() || values.isEmpty()) {
                break;
            }
            boolean trusted = true;
            for (JsonNode value : values) {
                if (!value.isString() || !policy.trustedValues().contains(
                        AnalyticsPropertyValueNormalizer.normalize(
                                value.asString(), AnalyticsPropertyDataType.STRING
                        ))) {
                    trusted = false;
                    break;
                }
            }
            if (trusted) return;
        }
        throw invalid("受治理指标的版本筛选必须是 trustedSchemaPolicy 可信值的 EQ/IN 子集");
    }

    private void validateActiveMetricsAgainstPolicy(String projectId, TrustedSchemaPolicy policy) {
        if (policy == null) return;
        jdbcTemplate.query("""
                SELECT metric_key, definition::text
                  FROM analytics_metric_definitions
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            try {
                validateMetricSchemaScope(
                        true, objectMapper.readTree(resultSet.getString("definition")), policy
                );
            } catch (BusinessException exception) {
                throw invalid("active metric[" + resultSet.getString("metric_key") + "] 不符合 trustedSchemaPolicy："
                        + exception.getMessage());
            }
        }, projectId);
    }

    private ValidatedPack validatePack(AnalysisPackImportRequest request) {
        if (request == null || request.packVersion() == null || request.packVersion() < 1
                || request.displayName() == null || request.displayName().isEmpty()
                || request.manifest() == null || !request.manifest().isObject()) {
            throw invalid("Analysis Pack 请求不完整");
        }
        validateLocalizedNames(request.displayName(), "displayName");
        JsonNode manifest = request.manifest();
        requireOnlyFields(manifest, ROOT_FIELDS, "manifest");
        if (manifest.path("schemaVersion").asInt(-1) != 1) {
            throw invalid("当前仅支持 Analysis Pack schemaVersion=1");
        }
        TrustedSchemaPolicy trustedSchemaPolicy = parseTrustedSchemaPolicy(manifest.get("trustedSchemaPolicy"));
        JsonNode properties = arrayOrEmpty(manifest.get("properties"), "properties", 200);
        JsonNode metrics = arrayOrEmpty(manifest.get("metrics"), "metrics", 200);
        for (JsonNode property : properties) {
            requireOnlyFields(property, PROPERTY_FIELDS, "properties[]");
            requiredText(property, "propertyKey", 80);
        }
        for (JsonNode metric : metrics) {
            requireOnlyFields(metric, METRIC_FIELDS, "metrics[]");
            requiredText(metric, "metricKey", 100);
            JsonNode definition = metric.get("definition");
            if (definition == null || !definition.isObject()) {
                throw invalid("metrics[].definition 必须是 object");
            }
            rejectExecutableFields(definition, "metrics[].definition");
        }
        return new ValidatedPack(trustedSchemaPolicy, iterable(properties), iterable(metrics));
    }

    private void validateMetricRequest(
            String projectId,
            AnalyticsMetricDefinitionRequest request,
            TrustedSchemaPolicy trustedSchemaPolicy
    ) {
        if (request == null || request.displayName() == null || request.displayName().isEmpty()
                || request.metricType() == null || request.definition() == null
                || !request.definition().isObject() || request.active() == null) {
            throw invalid("指标定义不完整");
        }
        validateLocalizedNames(request.displayName(), "displayName");
        if (request.description() != null && request.description().length() > 1000) {
            throw invalid("description 不能超过 1000 个字符");
        }
        rejectExecutableFields(request.definition(), "definition");
        Set<String> allowed = switch (request.metricType()) {
            case EVENT_COUNT, UNIQUE_ACTORS -> Set.of(
                    "semanticEvent", "propertyFilters", "schemaScope", "schemaScopeReason"
            );
            case FUNNEL_CONVERSION -> Set.of(
                    "steps", "groupBy", "journeyKey", "propertyFilters", "schemaScope", "schemaScopeReason"
            );
            case RETENTION -> Set.of(
                    "cohortEvent", "returnEvent", "days", "propertyFilters", "schemaScope", "schemaScopeReason"
            );
        };
        requireOnlyFields(request.definition(), allowed, "definition");
        JsonNode propertyFilters = request.definition().get("propertyFilters");
        if (propertyFilters != null && !propertyFilters.isNull()) {
            if (!propertyFilters.isArray()) throw invalid("definition.propertyFilters 必须是数组");
            propertyFilterService.compile(projectId, propertyFilters.toString(), "properties");
        }
        switch (request.metricType()) {
            case EVENT_COUNT, UNIQUE_ACTORS -> requireSemanticEvents(
                    projectId, List.of(requiredText(request.definition(), "semanticEvent", 100))
            );
            case FUNNEL_CONVERSION -> {
                JsonNode steps = request.definition().get("steps");
                if (steps == null || !steps.isArray() || steps.size() < 2 || steps.size() > 12) {
                    throw invalid("FUNNEL_CONVERSION.steps 必须包含 2 到 12 个事件");
                }
                List<String> semanticKeys = new java.util.ArrayList<>();
                steps.forEach(step -> {
                    if (!step.isString()) throw invalid("steps 包含无效事件");
                    String semanticKey = step.asString().strip();
                    if (semanticKey.isEmpty() || semanticKey.length() > 100) {
                        throw invalid("steps 包含无效事件");
                    }
                    semanticKeys.add(semanticKey);
                });
                if (new java.util.HashSet<>(semanticKeys).size() != semanticKeys.size()) {
                    throw invalid("FUNNEL_CONVERSION.steps 不能包含重复事件");
                }
                requireSemanticEvents(projectId, semanticKeys);
                propertyFilterService.requireGroupable(
                        projectId, optionalText(request.definition(), "groupBy")
                );
                propertyFilterService.requireJourneyKey(
                        projectId, optionalText(request.definition(), "journeyKey")
                );
            }
            case RETENTION -> {
                requireSemanticEvents(projectId, List.of(
                        requiredText(request.definition(), "cohortEvent", 100),
                        requiredText(request.definition(), "returnEvent", 100)
                ));
                JsonNode days = request.definition().get("days");
                if (days != null && (!days.isArray() || days.size() > 91)) {
                    throw invalid("RETENTION.days 必须是最多 91 项的数组");
                }
                java.util.Set<Integer> distinctDays = new java.util.HashSet<>();
                if (days != null) days.forEach(day -> {
                    if (!day.isInt() || day.asInt() < 0 || day.asInt() > 90) {
                        throw invalid("RETENTION.days 只支持 0 到 90");
                    }
                    if (!distinctDays.add(day.asInt())) {
                        throw invalid("RETENTION.days 不能重复");
                    }
                });
            }
        }
        validateMetricSchemaScope(request.active(), request.definition(), trustedSchemaPolicy);
    }

    private void requireSemanticEvents(String projectId, List<String> semanticKeys) {
        semanticDictionaryService.resolveActiveEventAliases(projectId, semanticKeys);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredText(node, field, 80);
    }

    private static void validateLocalizedNames(Map<String, String> names, String field) {
        if (names.size() > 16) throw invalid(field + " 不能超过 16 个地区");
        names.forEach((locale, name) -> {
            String normalizedLocale = locale == null ? "" : locale.strip();
            String normalizedName = name == null ? "" : name.strip();
            if (!normalizedLocale.matches("^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$")
                    || normalizedName.isEmpty() || normalizedName.length() > 200) {
                throw invalid(field + " 的地区或名称格式无效");
            }
        });
    }

    private static BusinessException invalidPackItem(String section, String key, Exception exception) {
        String reason = exception instanceof BusinessException businessException
                ? businessException.getMessage()
                : "字段类型或结构无效";
        return invalid("Analysis Pack " + section + "[" + key + "] 无效：" + reason);
    }

    private static void rejectExecutableFields(JsonNode node, String path) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("sql") || key.contains("script") || key.contains("url")
                        || key.contains("html") || key.contains("import")) {
                    throw invalid(path + " 不允许可执行或远程加载字段: " + entry.getKey());
                }
                rejectExecutableFields(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (node.isArray()) {
            node.forEach(item -> rejectExecutableFields(item, path));
        }
    }

    private String requireProject(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!PROJECT_ID.matcher(normalized).matches()) {
            throw BusinessException.invalidProject(normalized);
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_projects WHERE project_id = ?", Integer.class, normalized
        );
        if (count == null || count == 0) {
            throw BusinessException.invalidProject(normalized);
        }
        return normalized;
    }

    private static String requirePackKey(String value) {
        String key = value == null ? "" : value.strip();
        if (!PACK_KEY.matcher(key).matches()) throw invalid("packKey 格式无效");
        return key;
    }

    private static String requireMetricKey(String value) {
        String key = value == null ? "" : value.strip();
        if (!METRIC_KEY.matcher(key).matches()) throw invalid("metricKey 格式无效");
        return key;
    }

    private JsonNode arrayOrEmpty(JsonNode value, String field, int max) {
        if (value == null || value.isNull()) return objectMapper.createArrayNode();
        if (!value.isArray() || value.size() > max) throw invalid(field + " 必须是数组且最多 " + max + " 项");
        return value;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        java.util.ArrayList<JsonNode> result = new java.util.ArrayList<>();
        array.forEach(result::add);
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()
                || value.asString().length() > maxLength) {
            throw invalid(field + " 缺失或格式无效");
        }
        return value.asString();
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        if (node == null || !node.isObject()) throw invalid(path + " 必须是 object");
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) throw invalid(path + " 包含未知字段: " + field);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize analysis configuration", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored analysis configuration is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_ANALYSIS_CONFIGURATION", message);
    }

    private record ExistingPack(
            int version,
            String checksum,
            Map<String, String> displayName,
            JsonNode manifest,
            OffsetDateTime updatedAt
    ) {}

    private record TrustedSchemaPolicy(String propertyKey, List<String> trustedValues) {}

    private record ValidatedPack(
            TrustedSchemaPolicy trustedSchemaPolicy,
            List<JsonNode> properties,
            List<JsonNode> metrics
    ) {}
}
