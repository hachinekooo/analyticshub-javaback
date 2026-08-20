package com.github.analyticshub.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventCatalogEntry;
import com.github.analyticshub.dto.EventCatalogResponse;
import com.github.analyticshub.dto.SemanticAliasUpdateMode;
import com.github.analyticshub.dto.SemanticDefinitionResponse;
import com.github.analyticshub.dto.SemanticDefinitionOrigin;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticDefinitionsResponse;
import com.github.analyticshub.dto.SemanticDeleteResponse;
import com.github.analyticshub.dto.SemanticSourceKind;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Project-scoped semantic metadata stored in the system database.
 *
 * <p>Event facts are queried from the selected project datasource and are
 * merged with this metadata in Java. No cross-database join is used.</p>
 */
@Service
public class SemanticDictionaryService {

    private static final int MAX_PROJECT_ID_LENGTH = 50;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_DISPLAY_LANGUAGES = 16;
    private static final int MAX_DISPLAY_NAME_LENGTH = 200;
    private static final int MAX_CATEGORY_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_ALIASES = 500;

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");
    private static final Pattern SEMANTIC_KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");
    private static final Pattern LOCALE_KEY_PATTERN = Pattern.compile(
            "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$"
    );

    private static final TypeReference<LinkedHashMap<String, String>> DISPLAY_NAME_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate systemJdbcTemplate;
    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    public SemanticDictionaryService(
            JdbcTemplate systemJdbcTemplate,
            MultiDataSourceManager dataSourceManager,
            ObjectMapper objectMapper
    ) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SemanticDefinitionsResponse listDefinitions(String projectId, String sourceKind) {
        String normalizedProjectId = normalizeProjectId(projectId);
        SemanticSourceKind normalizedSourceKind = parseSourceKind(sourceKind);
        requireProject(normalizedProjectId, false);

        Map<String, List<String>> aliases = loadAliases(normalizedProjectId, normalizedSourceKind);
        List<SemanticDefinitionResponse> items = systemJdbcTemplate.query(
                """
                SELECT semantic_key, definition_origin, display_name::text, category, description, is_active,
                       created_at, updated_at
                  FROM analytics_semantic_definitions
                 WHERE project_id = ? AND source_kind = ?
                 ORDER BY semantic_key
                """,
                (rs, rowNum) -> new SemanticDefinitionResponse(
                        normalizedProjectId,
                        normalizedSourceKind,
                        rs.getString("semantic_key"),
                        SemanticDefinitionOrigin.valueOf(rs.getString("definition_origin")),
                        deserializeDisplayName(rs.getString("display_name")),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getBoolean("is_active"),
                        aliases.getOrDefault(rs.getString("semantic_key"), List.of()),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                normalizedProjectId,
                normalizedSourceKind.name()
        );
        return new SemanticDefinitionsResponse(normalizedProjectId, normalizedSourceKind, List.copyOf(items));
    }

    @Transactional(readOnly = true)
    public SemanticDefinitionResponse getDefinition(
            String projectId,
            String sourceKind,
            String semanticKey
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        SemanticSourceKind normalizedSourceKind = parseSourceKind(sourceKind);
        String normalizedSemanticKey = validateSemanticKey(semanticKey);
        requireProject(normalizedProjectId, false);
        return requireDefinition(normalizedProjectId, normalizedSourceKind, normalizedSemanticKey);
    }

    @Transactional
    public SemanticDefinitionResponse upsertDefinition(
            String projectId,
            String semanticKey,
            SemanticDefinitionUpsertRequest request
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        String normalizedSemanticKey = validateSemanticKey(semanticKey);
        ValidatedUpsert validated = validateUpsert(request);
        requireProject(normalizedProjectId, false);

        List<SemanticDefinitionOrigin> existingOrigins = systemJdbcTemplate.query(
                "SELECT definition_origin FROM analytics_semantic_definitions "
                        + "WHERE project_id = ? AND source_kind = ? AND semantic_key = ?",
                (rs, rowNum) -> SemanticDefinitionOrigin.valueOf(rs.getString("definition_origin")),
                normalizedProjectId,
                validated.sourceKind().name(),
                normalizedSemanticKey
        );
        SemanticDefinitionOrigin origin = existingOrigins.isEmpty()
                ? requireCustomNamespace(normalizedSemanticKey)
                : existingOrigins.getFirst();

        if (validated.aliasMode() == SemanticAliasUpdateMode.REPLACE) {
            rejectAliasesOwnedByAnotherDefinition(
                    normalizedProjectId,
                    validated.sourceKind(),
                    normalizedSemanticKey,
                    validated.aliases()
            );
        }

        systemJdbcTemplate.update(
                """
                INSERT INTO analytics_semantic_definitions
                    (project_id, source_kind, semantic_key, definition_origin,
                     display_name, category, description, is_active)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (project_id, source_kind, semantic_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    category = EXCLUDED.category,
                    description = EXCLUDED.description,
                    is_active = EXCLUDED.is_active
                """,
                normalizedProjectId,
                validated.sourceKind().name(),
                normalizedSemanticKey,
                origin.name(),
                serializeDisplayName(validated.displayName()),
                validated.category(),
                validated.description(),
                validated.active()
        );

        if (validated.aliasMode() == SemanticAliasUpdateMode.REPLACE) {
            replaceAliases(
                    normalizedProjectId,
                    validated.sourceKind(),
                    normalizedSemanticKey,
                    validated.aliases()
            );
        }

        return requireDefinition(normalizedProjectId, validated.sourceKind(), normalizedSemanticKey);
    }

    @Transactional
    public SemanticDeleteResponse deleteDefinition(String projectId, String sourceKind, String semanticKey) {
        String normalizedProjectId = normalizeProjectId(projectId);
        SemanticSourceKind normalizedSourceKind = parseSourceKind(sourceKind);
        String normalizedSemanticKey = validateSemanticKey(semanticKey);
        requireProject(normalizedProjectId, false);

        SemanticDefinitionResponse definition = requireDefinition(
                normalizedProjectId,
                normalizedSourceKind,
                normalizedSemanticKey
        );
        if (definition.origin() == SemanticDefinitionOrigin.OFFICIAL) {
            throw new BusinessException(
                    "OFFICIAL_SEMANTIC_DELETE_FORBIDDEN",
                    "官方语义 Key 不能删除，可停用或清空其原始事件映射",
                    HttpStatus.CONFLICT
            );
        }

        int deleted = systemJdbcTemplate.update(
                """
                DELETE FROM analytics_semantic_definitions
                 WHERE project_id = ? AND source_kind = ? AND semantic_key = ?
                """,
                normalizedProjectId,
                normalizedSourceKind.name(),
                normalizedSemanticKey
        );
        if (deleted == 0) {
            throw semanticNotFound(normalizedSemanticKey);
        }
        return new SemanticDeleteResponse(
                normalizedProjectId,
                normalizedSourceKind,
                normalizedSemanticKey,
                "语义定义已删除"
        );
    }

    @Transactional(readOnly = true)
    public EventCatalogResponse getEventCatalog(String projectId, String sourceKind) {
        String normalizedProjectId = normalizeProjectId(projectId);
        SemanticSourceKind normalizedSourceKind = parseSourceKind(sourceKind);
        if (normalizedSourceKind != SemanticSourceKind.EVENT_TYPE) {
            throw unsupportedSourceKind(sourceKind);
        }
        requireProject(normalizedProjectId, true);

        DataSource projectDataSource;
        String eventsTable;
        try {
            projectDataSource = dataSourceManager.getDataSource(normalizedProjectId);
            eventsTable = dataSourceManager.getTableName(normalizedProjectId, "events");
        } catch (RuntimeException exception) {
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }

        JdbcTemplate projectJdbcTemplate = new JdbcTemplate(projectDataSource);
        List<RawEventAggregate> aggregates = projectJdbcTemplate.query(
                String.format(
                        """
                        SELECT event_type, COUNT(*) AS event_count,
                               to_timestamp(MIN(event_timestamp) / 1000.0) AS first_seen_at,
                               to_timestamp(MAX(event_timestamp) / 1000.0) AS last_seen_at
                          FROM %s
                         WHERE project_id = ?
                         GROUP BY event_type
                         ORDER BY event_count DESC, event_type ASC
                        """,
                        eventsTable
                ),
                (rs, rowNum) -> new RawEventAggregate(
                        rs.getString("event_type"),
                        rs.getLong("event_count"),
                        rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant()
                ),
                normalizedProjectId
        );

        Map<String, SemanticResolution> resolutions = loadActiveResolutions(
                normalizedProjectId,
                normalizedSourceKind
        );
        List<EventCatalogEntry> items = aggregates.stream()
                .map(aggregate -> mergeCatalogEntry(aggregate, resolutions.get(aggregate.rawKey())))
                .toList();
        return new EventCatalogResponse(normalizedProjectId, normalizedSourceKind, items);
    }

    @Transactional(readOnly = true)
    public Map<String, String> resolveActiveEventSemanticKeys(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        requireProject(normalizedProjectId, false);
        Map<String, SemanticResolution> resolutions = loadActiveResolutions(
                normalizedProjectId,
                SemanticSourceKind.EVENT_TYPE
        );
        Map<String, String> result = new LinkedHashMap<>();
        resolutions.forEach((rawKey, resolution) -> result.put(rawKey, resolution.semanticKey()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves stable semantic keys to their raw event aliases.
     *
     * <p>An active definition with no aliases is valid and resolves to an empty list,
     * which lets a newly initialized dashboard render a clear zero-data state.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> resolveActiveEventAliases(
            String projectId,
            List<String> semanticKeys
    ) {
        return resolveActiveEventAliases(projectId, semanticKeys, true);
    }

    /**
     * Resolves optional reporting semantics without making a generic dashboard unavailable.
     * Missing or inactive definitions are returned as empty alias lists.
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> resolveAvailableActiveEventAliases(
            String projectId,
            List<String> semanticKeys
    ) {
        return resolveActiveEventAliases(projectId, semanticKeys, false);
    }

    private Map<String, List<String>> resolveActiveEventAliases(
            String projectId,
            List<String> semanticKeys,
            boolean requireAllDefinitions
    ) {
        String normalizedProjectId = normalizeProjectId(projectId);
        requireProject(normalizedProjectId, false);
        if (semanticKeys == null || semanticKeys.isEmpty()) {
            throw invalidSemanticRequest("semanticKeys 不能为空");
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String key : semanticKeys) {
            requested.add(validateSemanticKey(key));
        }

        String placeholders = String.join(",", Collections.nCopies(requested.size(), "?"));
        List<Object> arguments = new ArrayList<>(2 + requested.size());
        arguments.add(normalizedProjectId);
        arguments.add(SemanticSourceKind.EVENT_TYPE.name());
        arguments.addAll(requested);

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        systemJdbcTemplate.query(
                "SELECT semantic_key FROM analytics_semantic_definitions "
                        + "WHERE project_id = ? AND source_kind = ? AND is_active = TRUE "
                        + "AND semantic_key IN (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        aliases.put(rs.getString("semantic_key"), new ArrayList<>()),
                arguments.toArray()
        );
        if (requireAllDefinitions && aliases.size() != requested.size()) {
            List<String> missing = requested.stream().filter(key -> !aliases.containsKey(key)).toList();
            throw new BusinessException(
                    "SEMANTIC_DEFINITION_UNAVAILABLE",
                    "语义定义不存在或已停用: " + String.join(", ", missing),
                    HttpStatus.CONFLICT
            );
        }
        if (!aliases.isEmpty()) {
            String activePlaceholders = String.join(",", Collections.nCopies(aliases.size(), "?"));
            List<Object> aliasArguments = new ArrayList<>(2 + aliases.size());
            aliasArguments.add(normalizedProjectId);
            aliasArguments.add(SemanticSourceKind.EVENT_TYPE.name());
            aliasArguments.addAll(aliases.keySet());
            systemJdbcTemplate.query(
                    "SELECT semantic_key, raw_key FROM analytics_semantic_aliases "
                            + "WHERE project_id = ? AND source_kind = ? "
                            + "AND semantic_key IN (" + activePlaceholders + ") ORDER BY semantic_key, raw_key",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> aliases
                            .get(rs.getString("semantic_key"))
                            .add(rs.getString("raw_key")),
                    aliasArguments.toArray()
            );
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        requested.forEach(key -> result.put(key, List.copyOf(aliases.getOrDefault(key, List.of()))));
        return Collections.unmodifiableMap(result);
    }

    /** Resolves one collected raw event key to its active stable semantic key. */
    @Transactional(readOnly = true)
    public String resolveActiveEventSemanticKey(String projectId, String rawKey) {
        String normalizedProjectId = normalizeProjectId(projectId);
        requireProject(normalizedProjectId, false);
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        List<String> values = systemJdbcTemplate.queryForList(
                """
                SELECT d.semantic_key
                  FROM analytics_semantic_aliases a
                  JOIN analytics_semantic_definitions d
                    ON d.project_id = a.project_id
                   AND d.source_kind = a.source_kind
                   AND d.semantic_key = a.semantic_key
                 WHERE a.project_id = ? AND a.source_kind = 'EVENT_TYPE'
                   AND a.raw_key = ? AND d.is_active = TRUE
                """,
                String.class,
                normalizedProjectId,
                rawKey
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private EventCatalogEntry mergeCatalogEntry(RawEventAggregate aggregate, SemanticResolution resolution) {
        if (resolution == null) {
            return new EventCatalogEntry(
                    aggregate.rawKey(),
                    aggregate.rawKey(),
                    false,
                    Map.of("default", aggregate.rawKey()),
                    null,
                    null,
                    aggregate.eventCount(),
                    aggregate.firstSeenAt(),
                    aggregate.lastSeenAt()
            );
        }
        return new EventCatalogEntry(
                aggregate.rawKey(),
                resolution.semanticKey(),
                true,
                resolution.displayName(),
                resolution.category(),
                resolution.description(),
                aggregate.eventCount(),
                aggregate.firstSeenAt(),
                aggregate.lastSeenAt()
        );
    }

    private Map<String, SemanticResolution> loadActiveResolutions(
            String projectId,
            SemanticSourceKind sourceKind
    ) {
        Map<String, SemanticResolution> resolutions = new LinkedHashMap<>();
        systemJdbcTemplate.query(
                """
                SELECT a.raw_key, d.semantic_key, d.display_name::text, d.category, d.description
                  FROM analytics_semantic_aliases a
                  JOIN analytics_semantic_definitions d
                    ON d.project_id = a.project_id
                   AND d.source_kind = a.source_kind
                   AND d.semantic_key = a.semantic_key
                 WHERE a.project_id = ? AND a.source_kind = ? AND d.is_active = TRUE
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> resolutions.put(
                        rs.getString("raw_key"),
                        new SemanticResolution(
                                rs.getString("semantic_key"),
                                deserializeDisplayName(rs.getString("display_name")),
                                rs.getString("category"),
                                rs.getString("description")
                        )
                ),
                projectId,
                sourceKind.name()
        );
        return resolutions;
    }

    private Map<String, List<String>> loadAliases(String projectId, SemanticSourceKind sourceKind) {
        Map<String, List<String>> mutable = new LinkedHashMap<>();
        systemJdbcTemplate.query(
                """
                SELECT semantic_key, raw_key
                  FROM analytics_semantic_aliases
                 WHERE project_id = ? AND source_kind = ?
                 ORDER BY semantic_key, raw_key
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> mutable
                        .computeIfAbsent(rs.getString("semantic_key"), ignored -> new ArrayList<>())
                        .add(rs.getString("raw_key")),
                projectId,
                sourceKind.name()
        );

        Map<String, List<String>> immutable = new LinkedHashMap<>();
        mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return immutable;
    }

    private SemanticDefinitionResponse requireDefinition(
            String projectId,
            SemanticSourceKind sourceKind,
            String semanticKey
    ) {
        List<String> aliases = systemJdbcTemplate.queryForList(
                """
                SELECT raw_key
                  FROM analytics_semantic_aliases
                 WHERE project_id = ? AND source_kind = ? AND semantic_key = ?
                 ORDER BY raw_key
                """,
                String.class,
                projectId,
                sourceKind.name(),
                semanticKey
        );
        List<SemanticDefinitionResponse> definitions = systemJdbcTemplate.query(
                """
                SELECT definition_origin, display_name::text, category, description, is_active,
                       created_at, updated_at
                  FROM analytics_semantic_definitions
                 WHERE project_id = ? AND source_kind = ? AND semantic_key = ?
                """,
                (rs, rowNum) -> new SemanticDefinitionResponse(
                        projectId,
                        sourceKind,
                        semanticKey,
                        SemanticDefinitionOrigin.valueOf(rs.getString("definition_origin")),
                        deserializeDisplayName(rs.getString("display_name")),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getBoolean("is_active"),
                        List.copyOf(aliases),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                projectId,
                sourceKind.name(),
                semanticKey
        );
        if (definitions.isEmpty()) {
            throw semanticNotFound(semanticKey);
        }
        return definitions.getFirst();
    }

    private void rejectAliasesOwnedByAnotherDefinition(
            String projectId,
            SemanticSourceKind sourceKind,
            String semanticKey,
            List<String> aliases
    ) {
        if (aliases.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(aliases.size(), "?"));
        List<Object> args = new ArrayList<>(4 + aliases.size());
        args.add(projectId);
        args.add(sourceKind.name());
        args.add(semanticKey);
        args.addAll(aliases);
        List<String> conflicts = systemJdbcTemplate.queryForList(
                "SELECT raw_key FROM analytics_semantic_aliases "
                        + "WHERE project_id = ? AND source_kind = ? AND semantic_key <> ? "
                        + "AND raw_key IN (" + placeholders + ") ORDER BY raw_key",
                String.class,
                args.toArray()
        );
        if (!conflicts.isEmpty()) {
            throw aliasConflict();
        }
    }

    private void replaceAliases(
            String projectId,
            SemanticSourceKind sourceKind,
            String semanticKey,
            List<String> aliases
    ) {
        systemJdbcTemplate.update(
                """
                DELETE FROM analytics_semantic_aliases
                 WHERE project_id = ? AND source_kind = ? AND semantic_key = ?
                """,
                projectId,
                sourceKind.name(),
                semanticKey
        );
        if (aliases.isEmpty()) {
            return;
        }

        try {
            systemJdbcTemplate.batchUpdate(
                    """
                    INSERT INTO analytics_semantic_aliases
                        (project_id, source_kind, raw_key, semantic_key)
                    VALUES (?, ?, ?, ?)
                    """,
                    aliases,
                    aliases.size(),
                    (statement, rawKey) -> {
                        statement.setString(1, projectId);
                        statement.setString(2, sourceKind.name());
                        statement.setString(3, rawKey);
                        statement.setString(4, semanticKey);
                    }
            );
        } catch (DataIntegrityViolationException exception) {
            throw aliasConflict();
        }
    }

    private void requireProject(String projectId, boolean requireActive) {
        List<Boolean> states = systemJdbcTemplate.query(
                "SELECT is_active FROM analytics_projects WHERE project_id = ?",
                (rs, rowNum) -> rs.getBoolean("is_active"),
                projectId
        );
        if (states.isEmpty()) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
        }
        if (requireActive && !Boolean.TRUE.equals(states.getFirst())) {
            throw BusinessException.projectInactive();
        }
    }

    private ValidatedUpsert validateUpsert(SemanticDefinitionUpsertRequest request) {
        if (request == null) {
            throw invalidSemanticRequest("请求体不能为空");
        }
        SemanticSourceKind sourceKind = request.sourceKind();
        if (sourceKind == null) {
            throw invalidSemanticRequest("sourceKind 不能为空");
        }
        if (sourceKind != SemanticSourceKind.EVENT_TYPE) {
            throw unsupportedSourceKind(sourceKind.name());
        }
        Map<String, String> displayName = validateDisplayName(request.displayName());
        String category = normalizeOptionalText(request.category(), MAX_CATEGORY_LENGTH, "category");
        String description = normalizeOptionalText(request.description(), MAX_DESCRIPTION_LENGTH, "description");
        if (request.isActive() == null) {
            throw invalidSemanticRequest("isActive 不能为空");
        }
        if (request.aliasMode() == null) {
            throw invalidSemanticRequest("aliasMode 不能为空");
        }

        List<String> aliases;
        if (request.aliasMode() == SemanticAliasUpdateMode.PRESERVE) {
            if (request.aliases() != null) {
                throw invalidSemanticRequest("aliasMode=PRESERVE 时不得传 aliases");
            }
            aliases = List.of();
        } else {
            if (request.aliases() == null) {
                throw invalidSemanticRequest("aliasMode=REPLACE 时必须传 aliases，可传空数组清空映射");
            }
            aliases = validateAliases(request.aliases());
        }

        return new ValidatedUpsert(
                sourceKind,
                displayName,
                category,
                description,
                request.isActive(),
                request.aliasMode(),
                aliases
        );
    }

    private static Map<String, String> validateDisplayName(Map<String, String> displayName) {
        if (displayName == null || displayName.isEmpty() || displayName.size() > MAX_DISPLAY_LANGUAGES) {
            throw invalidSemanticRequest("displayName 必须包含1到16个语言项");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        Set<String> normalizedLocaleKeys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : displayName.entrySet()) {
            String localeKey = entry.getKey();
            String value = entry.getValue();
            if (localeKey == null || !LOCALE_KEY_PATTERN.matcher(localeKey).matches()) {
                throw invalidSemanticRequest("displayName 的语言 key 格式无效");
            }
            if (!normalizedLocaleKeys.add(localeKey.toLowerCase(Locale.ROOT))) {
                throw invalidSemanticRequest("displayName 的语言 key 忽略大小写后不能重复");
            }
            if (value == null || value.isBlank()) {
                throw invalidSemanticRequest("displayName 的展示名称不能为空");
            }
            String normalizedValue = value.strip();
            if (normalizedValue.length() > MAX_DISPLAY_NAME_LENGTH || containsUnsafeControl(normalizedValue)) {
                throw invalidSemanticRequest("displayName 的展示名称格式无效或长度超限");
            }
            normalized.put(localeKey, normalizedValue);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static List<String> validateAliases(List<String> aliases) {
        if (aliases.size() > MAX_ALIASES) {
            throw invalidSemanticRequest("aliases 最多支持500项");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String alias : aliases) {
            // Raw aliases must accept the same naming diversity as collected event_type values.
            // They are always bound as SQL parameters, so no identifier-style allow-list is needed.
            if (alias == null || alias.isBlank() || alias.length() > MAX_KEY_LENGTH) {
                throw invalidSemanticRequest("alias key 格式无效");
            }
            if (!unique.add(alias)) {
                throw invalidSemanticRequest("aliases 不能包含重复 key");
            }
        }
        return List.copyOf(unique);
    }

    private static String normalizeProjectId(String projectId) {
        if (projectId == null || projectId.length() > MAX_PROJECT_ID_LENGTH
                || !PROJECT_ID_PATTERN.matcher(projectId).matches()) {
            throw new BusinessException("INVALID_PROJECT", "projectId 格式无效", HttpStatus.BAD_REQUEST);
        }
        return projectId;
    }

    private static String validateSemanticKey(String semanticKey) {
        if (semanticKey == null || semanticKey.length() > MAX_KEY_LENGTH
                || !SEMANTIC_KEY_PATTERN.matcher(semanticKey).matches()) {
            throw invalidSemanticRequest("semanticKey 格式无效，应使用小写字母、数字、点、下划线或连字符");
        }
        return semanticKey;
    }

    private static SemanticDefinitionOrigin requireCustomNamespace(String semanticKey) {
        if (!semanticKey.startsWith("custom.") || semanticKey.length() <= "custom.".length()) {
            throw invalidSemanticRequest("自定义语义 Key 必须使用 custom.* 命名空间");
        }
        return SemanticDefinitionOrigin.CUSTOM;
    }

    private static String normalizeOptionalText(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength || containsUnsafeControl(normalized)) {
            throw invalidSemanticRequest(field + " 格式无效或长度超限");
        }
        return normalized;
    }

    private static boolean containsUnsafeControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT);
    }

    static SemanticSourceKind parseSourceKind(String sourceKind) {
        if (sourceKind == null) {
            throw unsupportedSourceKind(null);
        }
        try {
            return SemanticSourceKind.valueOf(sourceKind);
        } catch (IllegalArgumentException exception) {
            throw unsupportedSourceKind(sourceKind);
        }
    }

    private String serializeDisplayName(Map<String, String> displayName) {
        try {
            return objectMapper.writeValueAsString(displayName);
        } catch (JacksonException exception) {
            throw new BusinessException(
                    "SEMANTIC_DISPLAY_NAME_INVALID",
                    "displayName 无法序列化",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private Map<String, String> deserializeDisplayName(String json) {
        try {
            return Collections.unmodifiableMap(objectMapper.readValue(json, DISPLAY_NAME_TYPE));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored semantic display_name is invalid", exception);
        }
    }

    private static BusinessException invalidSemanticRequest(String message) {
        return new BusinessException("INVALID_SEMANTIC_DEFINITION", message, HttpStatus.BAD_REQUEST);
    }

    private static BusinessException unsupportedSourceKind(String sourceKind) {
        return new BusinessException(
                "UNSUPPORTED_SEMANTIC_SOURCE_KIND",
                "1.0.1 仅支持 sourceKind=EVENT_TYPE",
                HttpStatus.BAD_REQUEST
        );
    }

    private static BusinessException aliasConflict() {
        return new BusinessException(
                "SEMANTIC_ALIAS_CONFLICT",
                "alias 已被其他 semanticKey 占用",
                HttpStatus.CONFLICT
        );
    }

    private static BusinessException semanticNotFound(String semanticKey) {
        return new BusinessException(
                "SEMANTIC_DEFINITION_NOT_FOUND",
                "语义定义不存在: " + semanticKey,
                HttpStatus.NOT_FOUND
        );
    }

    private record ValidatedUpsert(
            SemanticSourceKind sourceKind,
            Map<String, String> displayName,
            String category,
            String description,
            boolean active,
            SemanticAliasUpdateMode aliasMode,
            List<String> aliases
    ) {
    }

    private record RawEventAggregate(
            String rawKey,
            long eventCount,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    private record SemanticResolution(
            String semanticKey,
            Map<String, String> displayName,
            String category,
            String description
    ) {
    }
}
