package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyFilter;
import com.github.analyticshub.dto.AnalyticsPropertyFilterOperator;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 把受限属性筛选合同编译为参数化 SQL；不接受 JSONPath、表达式或任意 SQL。 */
@Service
public class AnalyticsPropertyFilterService {

    private static final int MAX_FILTERS = 12;
    private static final int MAX_IN_VALUES = 20;
    private static final int MAX_SERIALIZED_BYTES = 8_192;
    private static final TypeReference<List<AnalyticsPropertyFilter>> FILTER_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final AnalyticsPropertyDefinitionService definitionService;

    public AnalyticsPropertyFilterService(
            ObjectMapper objectMapper,
            AnalyticsPropertyDefinitionService definitionService
    ) {
        this.objectMapper = objectMapper;
        this.definitionService = definitionService;
    }

    public CompiledPropertyFilters compile(String projectId, String encodedFilters, String propertiesColumn) {
        if (encodedFilters == null || encodedFilters.isBlank()) {
            return CompiledPropertyFilters.empty();
        }
        if (encodedFilters.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES) {
            throw invalid("propertyFilters 体积超过 8KB");
        }
        List<AnalyticsPropertyFilter> filters;
        try {
            filters = objectMapper.readValue(encodedFilters, FILTER_LIST);
        } catch (Exception exception) {
            throw invalid("propertyFilters 必须是合法 JSON 数组");
        }
        if (filters == null || filters.isEmpty()) {
            return CompiledPropertyFilters.empty();
        }
        if (filters.size() > MAX_FILTERS) {
            throw invalid("propertyFilters 最多支持 " + MAX_FILTERS + " 项");
        }

        if (filters.stream().anyMatch(filter -> filter == null
                || filter.propertyKey() == null || filter.operator() == null)) {
            throw invalid("propertyFilters 存在不完整项");
        }
        List<String> requestedKeys = filters.stream().map(AnalyticsPropertyFilter::propertyKey).toList();
        Map<String, AnalyticsPropertyDefinitionResponse> definitions =
                definitionService.requireCapabilities(projectId, requestedKeys);
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (AnalyticsPropertyFilter filter : filters) {
            if (!seenKeys.add(filter.propertyKey())) {
                throw invalid("同一属性不能重复筛选");
            }
            AnalyticsPropertyDefinitionResponse definition = definitions.get(filter.propertyKey());
            if (definition == null || !definition.active() || !definition.filterable() || definition.sensitive()) {
                throw invalid("属性未启用筛选能力: " + filter.propertyKey());
            }
            List<String> values = normalizeValues(filter, definition);
            if (filter.operator() == AnalyticsPropertyFilterOperator.EXISTS) {
                clauses.add("jsonb_exists(" + propertiesColumn + ", ?)");
                arguments.add(filter.propertyKey());
                continue;
            }
            List<String> comparisons = new ArrayList<>();
            for (String value : values) {
                comparisons.add(comparisonSql(definition.dataType(), propertiesColumn));
                addComparisonArguments(arguments, definition.dataType(), filter.propertyKey(), value);
            }
            clauses.add("(" + String.join(" OR ", comparisons) + ")");
        }
        return new CompiledPropertyFilters(String.join(" AND ", clauses), List.copyOf(arguments));
    }

    public AnalyticsPropertyDataType requireGroupable(String projectId, String propertyKey) {
        return requireCapability(projectId, propertyKey, Capability.GROUPABLE);
    }

    public void requireJourneyKey(String projectId, String propertyKey) {
        requireCapability(projectId, propertyKey, Capability.JOURNEY_KEY);
    }

    public AnalyticsPropertyDataType requireNumericSummary(String projectId, String propertyKey) {
        AnalyticsPropertyDataType type = requireCapability(projectId, propertyKey, Capability.NUMERIC_SUMMARY);
        if (type != AnalyticsPropertyDataType.INTEGER && type != AnalyticsPropertyDataType.NUMBER) {
            throw invalid("数值摘要只支持 INTEGER / NUMBER 属性: " + propertyKey);
        }
        return type;
    }

    private AnalyticsPropertyDataType requireCapability(String projectId, String propertyKey, Capability capability) {
        if (propertyKey == null || propertyKey.isBlank()) {
            return null;
        }
        // 升级后尚未建立属性字典的旧项目继续读取既有 Dashboard；一旦开始治理，所有新旧配置统一受 allowlist 约束。
        if (definitionService.list(projectId).items().isEmpty()) {
            return null;
        }
        AnalyticsPropertyDefinitionResponse definition = definitionService
                .requireCapabilities(projectId, List.of(propertyKey)).get(propertyKey);
        boolean allowed = definition != null && definition.active() && !definition.sensitive()
                && switch (capability) {
                    case GROUPABLE -> definition.groupable();
                    case JOURNEY_KEY -> definition.journeyKey();
                    case NUMERIC_SUMMARY -> definition.filterable();
                };
        if (!allowed) {
            throw invalid("属性未启用" + capability.displayName + "能力: " + propertyKey);
        }
        return definition.dataType();
    }

    private static List<String> normalizeValues(
            AnalyticsPropertyFilter filter,
            AnalyticsPropertyDefinitionResponse definition
    ) {
        List<String> raw = filter.values() == null ? List.of() : filter.values();
        if (filter.operator() == AnalyticsPropertyFilterOperator.EXISTS) {
            if (!raw.isEmpty()) {
                throw invalid("EXISTS 不接受 values");
            }
            return List.of();
        }
        int expectedMax = filter.operator() == AnalyticsPropertyFilterOperator.EQ ? 1 : MAX_IN_VALUES;
        if (raw.isEmpty() || raw.size() > expectedMax
                || (filter.operator() == AnalyticsPropertyFilterOperator.EQ && raw.size() != 1)) {
            throw invalid(filter.operator() + " 的 values 数量无效");
        }
        List<String> normalized = raw.stream().map(value -> normalizeValue(value, definition.dataType())).toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw invalid("values 不能重复");
        }
        List<String> allowed = definition.allowedValues();
        if (allowed != null && !allowed.isEmpty() && !allowed.containsAll(normalized)) {
            throw invalid("筛选值不在属性允许值域中: " + filter.propertyKey());
        }
        return normalized;
    }

    private static String normalizeValue(String value, AnalyticsPropertyDataType type) {
        try {
            return AnalyticsPropertyValueNormalizer.normalize(value, type);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    private static String comparisonSql(AnalyticsPropertyDataType type, String column) {
        return switch (type) {
            case STRING -> "(jsonb_typeof(" + column + " -> ?) = 'string' AND btrim(" + column
                    + " ->> ?, E' \\t\\n\\r\\f') = ?)";
            case BOOLEAN -> "(jsonb_typeof(" + column + " -> ?) = 'boolean' AND (" + column
                    + " ->> ?)::boolean = CAST(? AS boolean))";
            case INTEGER, NUMBER -> "(jsonb_typeof(" + column + " -> ?) = 'number' AND (" + column
                    + " ->> ?)::numeric = CAST(? AS numeric))";
        };
    }

    private static void addComparisonArguments(
            List<Object> arguments,
            AnalyticsPropertyDataType type,
            String propertyKey,
            String value
    ) {
        arguments.add(propertyKey);
        arguments.add(propertyKey);
        arguments.add(value);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_ANALYTICS_PROPERTY_FILTER", message);
    }

    public record CompiledPropertyFilters(String sql, List<Object> arguments) {
        public static CompiledPropertyFilters empty() {
            return new CompiledPropertyFilters("", List.of());
        }

        public boolean isEmpty() {
            return sql.isBlank();
        }
    }

    private enum Capability {
        GROUPABLE("分组"),
        JOURNEY_KEY("旅程关联"),
        NUMERIC_SUMMARY("数值摘要");

        private final String displayName;

        Capability(String displayName) {
            this.displayName = displayName;
        }
    }
}
