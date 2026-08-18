package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.extension.dashboard.DashboardWidgetExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates declarative dashboard JSON.
 *
 * <p>The contract is intentionally allow-list based. Dashboard definitions may
 * select built-in widgets and data parameters, but cannot carry executable
 * HTML, JavaScript, SQL, arbitrary URLs or dynamic import instructions.</p>
 */
@Component
public class DashboardDefinitionValidator {

    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_DEFINITION_BYTES = 256 * 1024;
    private static final int MAX_WIDGETS = 50;
    private static final Pattern LOCALE_PATTERN = Pattern.compile(
            "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$"
    );
    private static final Pattern EXTENSION_TYPE_PATTERN = Pattern.compile(
            "^custom\\.[A-Za-z0-9][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9][A-Za-z0-9_-]*)*$"
    );
    private static final Pattern EXTENSION_FIELD_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]{0,63}$"
    );

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "defaultRange", "widgets"
    );
    private static final Set<String> WIDGET_FIELDS = Set.of(
            "id", "type", "layout", "config"
    );
    private static final Set<String> LAYOUT_FIELDS = Set.of(
            "x", "y", "w", "h", "minW", "minH"
    );
    private static final Set<String> COMMON_CONFIG_FIELDS = Set.of("title");
    private static final Set<String> CORE_WIDGET_TYPES = Set.of(
            "core.overview",
            "core.trends",
            "core.topEvents",
            "core.productFunnel",
            "core.retention",
            "core.trafficOverview",
            "core.trafficTrends",
            "core.topPages",
            "core.topReferrers",
            "core.counters",
            "core.events",
            "core.devices",
            "core.sessions",
            "core.traffic"
    );

    private static final Map<String, Set<String>> TYPE_CONFIG_FIELDS = Map.ofEntries(
            Map.entry("core.overview", Set.of()),
            Map.entry("core.trends", Set.of("granularity")),
            Map.entry("core.topEvents", Set.of("aggregation", "limit")),
            Map.entry("core.productFunnel", Set.of("steps", "groupBy", "journeyKey")),
            Map.entry("core.retention", Set.of("cohortEvent", "returnEvent", "days")),
            Map.entry("core.trafficOverview", Set.of()),
            Map.entry("core.trafficTrends", Set.of("granularity")),
            Map.entry("core.topPages", Set.of("limit")),
            Map.entry("core.topReferrers", Set.of("limit")),
            Map.entry("core.counters", Set.of("keys")),
            Map.entry("core.events", Set.of("eventType", "pageSize")),
            Map.entry("core.devices", Set.of("pageSize")),
            Map.entry("core.sessions", Set.of("pageSize")),
            Map.entry("core.traffic", Set.of("metricType", "pageSize"))
    );

    private final Map<String, RegisteredExtension> extensions;

    public DashboardDefinitionValidator() {
        this(List.of());
    }

    @Autowired
    public DashboardDefinitionValidator(List<DashboardWidgetExtension> extensions) {
        if (extensions == null) {
            throw new IllegalArgumentException("Dashboard extensions 不能为空");
        }
        Map<String, RegisteredExtension> byType = new LinkedHashMap<>();
        for (DashboardWidgetExtension extension : extensions) {
            String type = extension == null ? null : extension.type();
            if (type == null || type.length() > 100
                    || !EXTENSION_TYPE_PATTERN.matcher(type).matches()) {
                throw new IllegalArgumentException(
                        "Dashboard extension type 必须使用 custom.* 命名空间且长度不超过 100"
                );
            }
            Set<String> fields = extension.allowedConfigFields();
            if (fields == null) {
                throw new IllegalArgumentException(
                        "Dashboard extension allowedConfigFields 不能为空: " + type
                );
            }
            for (String field : fields) {
                if (field == null || "title".equals(field)
                        || !EXTENSION_FIELD_PATTERN.matcher(field).matches()) {
                    throw new IllegalArgumentException(
                            "Dashboard extension config field 格式无效: " + type
                    );
                }
            }
            RegisteredExtension registered = new RegisteredExtension(
                    extension,
                    Set.copyOf(fields),
                    extension.configRequired()
            );
            if (byType.putIfAbsent(type, registered) != null) {
                throw new IllegalArgumentException(
                        "Dashboard extension type 不能重复: " + type
                );
            }
        }
        this.extensions = Map.copyOf(byType);
    }

    public void validate(int schemaVersion, JsonNode definition) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            fail("当前仅支持 dashboard schemaVersion=1");
        }
        if (definition == null || !definition.isObject()) {
            fail("definition 必须是 JSON object");
        }
        if (definition.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
            fail("definition 不能超过 256 KiB");
        }
        requireOnlyFields(definition, ROOT_FIELDS, "definition");
        requireInteger(definition.get("schemaVersion"), schemaVersion, schemaVersion, "schemaVersion");

        JsonNode defaultRange = definition.get("defaultRange");
        if (defaultRange != null && !defaultRange.isNull()) {
            requireText(defaultRange, 16, "defaultRange");
            if (!Set.of("24h", "7d", "30d", "90d", "custom").contains(defaultRange.asString())) {
                fail("defaultRange 仅支持 24h / 7d / 30d / 90d / custom");
            }
        }

        JsonNode widgets = definition.get("widgets");
        if (widgets == null || !widgets.isArray()) {
            fail("widgets 必须是 array");
        }
        if (widgets.size() > MAX_WIDGETS) {
            fail("widgets 数量不能超过 50");
        }

        Set<String> widgetIds = new HashSet<>();
        Set<String> widgetTypes = new HashSet<>();
        for (int index = 0; index < widgets.size(); index++) {
            validateWidget(widgets.get(index), index, widgetIds, widgetTypes);
        }
    }

    public void validateDisplayName(JsonNode displayName) {
        if (displayName == null || !displayName.isObject() || displayName.isEmpty()) {
            fail("displayName 必须是非空的多语言 JSON object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = displayName.properties().iterator();
        int count = 0;
        Set<String> normalizedLocales = new HashSet<>();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            count++;
            if (!LOCALE_PATTERN.matcher(field.getKey()).matches()
                    || field.getKey().length() > 32) {
                fail("displayName 语言 key 格式无效");
            }
            if (!normalizedLocales.add(field.getKey().toLowerCase(java.util.Locale.ROOT))) {
                fail("displayName 语言 key 不能重复（忽略大小写）");
            }
            requireText(field.getValue(), 100, "displayName." + field.getKey());
        }
        if (count > 20) {
            fail("displayName 最多支持 20 种语言");
        }
    }

    private void validateWidget(
            JsonNode widget,
            int index,
            Set<String> widgetIds,
            Set<String> widgetTypes
    ) {
        String path = "widgets[" + index + "]";
        if (widget == null || !widget.isObject()) {
            fail(path + " 必须是 object");
        }
        requireOnlyFields(widget, WIDGET_FIELDS, path);

        String id = requireText(widget.get("id"), 100, path + ".id");
        if (!id.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")) {
            fail(path + ".id 格式无效");
        }
        if (!widgetIds.add(id)) {
            fail("widget id 不能重复: " + id);
        }

        String type = requireText(widget.get("type"), 100, path + ".type");
        if (!CORE_WIDGET_TYPES.contains(type) && !extensions.containsKey(type)) {
            fail("不支持的 widget type: " + type);
        }
        if (!widgetTypes.add(type)) {
            fail("dashboard schemaVersion=1 中同一 widget type 只能出现一次: " + type);
        }
        validateLayout(widget.get("layout"), path + ".layout");
        validateConfig(type, widget.get("config"), path + ".config");
    }

    private void validateLayout(JsonNode layout, String path) {
        if (layout == null || !layout.isObject()) {
            fail(path + " 必须是 object");
        }
        requireOnlyFields(layout, LAYOUT_FIELDS, path);
        int x = requireInteger(layout.get("x"), 0, 11, path + ".x");
        requireInteger(layout.get("y"), 0, 10_000, path + ".y");
        int width = requireInteger(layout.get("w"), 1, 12, path + ".w");
        int height = requireInteger(layout.get("h"), 1, 100, path + ".h");
        if (x + width > 12) {
            fail(path + " 超出 12 列网格");
        }
        if (layout.has("minW")) {
            int minWidth = requireInteger(layout.get("minW"), 1, 12, path + ".minW");
            if (minWidth > width) {
                fail(path + ".minW 不能大于 w");
            }
        }
        if (layout.has("minH")) {
            int minHeight = requireInteger(layout.get("minH"), 1, 100, path + ".minH");
            if (minHeight > height) {
                fail(path + ".minH 不能大于 h");
            }
        }
    }

    private void validateConfig(String type, JsonNode config, String path) {
        RegisteredExtension registered = extensions.get(type);
        if (config == null || config.isNull()) {
            if (Set.of("core.productFunnel", "core.retention").contains(type)
                    || (registered != null && registered.configRequired())) {
                fail(path + " 是 " + type + " 的必填 object");
            }
            return;
        }
        if (!config.isObject()) {
            fail(path + " 必须是 object");
        }

        Set<String> allowed = new HashSet<>(COMMON_CONFIG_FIELDS);
        if (registered == null) {
            allowed.addAll(TYPE_CONFIG_FIELDS.getOrDefault(type, Set.of()));
        } else {
            allowed.addAll(registered.allowedConfigFields());
        }
        requireOnlyFields(config, allowed, path);
        if (config.has("title")) {
            requireText(config.get("title"), 100, path + ".title");
        }

        if (registered != null) {
            try {
                registered.extension().validateConfig(config);
            } catch (IllegalArgumentException exception) {
                String message = exception.getMessage();
                fail(path + " 校验失败" + (message == null || message.isBlank() ? "" : ": " + message));
            }
            return;
        }

        switch (type) {
            case "core.trends" -> validateGranularity(config, path, Set.of("hour", "day"));
            case "core.trafficTrends" -> validateGranularity(
                    config,
                    path,
                    Set.of("hour", "day", "week", "month", "year")
            );
            case "core.topEvents" -> {
                if (config.has("aggregation")) {
                    String aggregation = requireText(config.get("aggregation"), 16, path + ".aggregation");
                    if (!Set.of("raw", "semantic").contains(aggregation)) {
                        fail(path + ".aggregation 仅支持 raw / semantic");
                    }
                }
                validateOptionalInteger(config, "limit", 1, 50, path);
            }
            case "core.productFunnel" -> validateFunnelConfig(config, path);
            case "core.retention" -> validateRetentionConfig(config, path);
            case "core.topPages", "core.topReferrers" ->
                    validateOptionalInteger(config, "limit", 1, 100, path);
            case "core.counters" -> {
                if (config.has("keys")) {
                    validateStringArray(config.get("keys"), 1, 20, path + ".keys");
                }
            }
            case "core.events" -> {
                validateOptionalEventKey(config, "eventType", path);
                validateOptionalInteger(config, "pageSize", 1, 200, path);
            }
            case "core.devices", "core.sessions" ->
                    validateOptionalInteger(config, "pageSize", 1, 200, path);
            case "core.traffic" -> {
                validateOptionalEventKey(config, "metricType", path);
                validateOptionalInteger(config, "pageSize", 1, 200, path);
            }
            default -> {
                // Widgets without type-specific configuration only accept the common title.
            }
        }
    }

    private void validateGranularity(JsonNode config, String path, Set<String> allowed) {
        if (!config.has("granularity")) {
            return;
        }
        String granularity = requireText(config.get("granularity"), 16, path + ".granularity");
        if (!allowed.contains(granularity)) {
            fail(path + ".granularity 格式无效");
        }
    }

    private void validateFunnelConfig(JsonNode config, String path) {
        validateAnalyticsEventArray(config.get("steps"), 2, 12, path + ".steps");
        if (config.has("groupBy")) {
            requireAnalyticsKey(config.get("groupBy"), 80, path + ".groupBy");
        }
        if (config.has("journeyKey")) {
            requireAnalyticsKey(config.get("journeyKey"), 80, path + ".journeyKey");
        }
    }

    private void validateRetentionConfig(JsonNode config, String path) {
        requireAnalyticsKey(config.get("cohortEvent"), 100, path + ".cohortEvent");
        requireAnalyticsKey(config.get("returnEvent"), 100, path + ".returnEvent");
        JsonNode days = config.get("days");
        if (days == null || !days.isArray() || days.isEmpty() || days.size() > 30) {
            fail(path + ".days 必须包含 1 到 30 个天数");
        }
        Set<Integer> values = new HashSet<>();
        for (JsonNode day : days) {
            int value = requireInteger(day, 0, 90, path + ".days[]");
            if (!values.add(value)) {
                fail(path + ".days 不能包含重复值: " + value);
            }
        }
    }

    private void validateAnalyticsEventArray(JsonNode node, int min, int max, String path) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            fail(path + " 数量必须在 " + min + " 到 " + max + " 之间");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            String eventName = requireAnalyticsKey(value, 100, path + "[]");
            if (!values.add(eventName)) {
                fail(path + " 不能包含重复值: " + eventName);
            }
        }
    }

    private String requireAnalyticsKey(JsonNode value, int maxLength, String path) {
        String key = requireText(value, maxLength, path);
        if (!key.matches("[A-Za-z0-9_.:-]{1," + maxLength + "}")) {
            fail(path + " 格式无效");
        }
        return key;
    }

    private void validateStringArray(JsonNode node, int min, int max, String path) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            fail(path + " 数量必须在 " + min + " 到 " + max + " 之间");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            String normalizedValue = requireEventKey(value, path + "[]");
            if (!values.add(normalizedValue)) {
                fail(path + " 不能包含重复值: " + normalizedValue);
            }
        }
    }

    private void validateOptionalEventKey(JsonNode config, String field, String path) {
        if (config.has(field)) {
            requireEventKey(config.get(field), path + "." + field);
        }
    }

    private String requireEventKey(JsonNode value, String path) {
        return requireText(value, 100, path);
    }

    private void validateOptionalInteger(
            JsonNode config,
            String field,
            int min,
            int max,
            String path
    ) {
        if (config.has(field)) {
            requireInteger(config.get(field), min, max, path + "." + field);
        }
    }

    private static int requireInteger(JsonNode value, int min, int max, String path) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            fail(path + " 必须是整数");
        }
        int number = value.intValue();
        if (number < min || number > max) {
            fail(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return number;
    }

    private static String requireText(JsonNode value, int maxLength, String path) {
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            fail(path + " 必须是非空字符串");
        }
        String text = value.stringValue();
        if (text.length() > maxLength) {
            fail(path + " 长度不能超过 " + maxLength);
        }
        if (!text.equals(text.strip())) {
            fail(path + " 不能包含首尾空白");
        }
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            fail(path + " 不能包含控制字符");
        }
        return text;
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        List<String> unsupported = new ArrayList<>();
        node.propertyNames().forEach(field -> {
            if (!allowed.contains(field)) {
                unsupported.add(field);
            }
        });
        if (!unsupported.isEmpty()) {
            fail(path + " 包含不支持的字段: " + String.join(", ", unsupported));
        }
    }

    private static void fail(String message) {
        throw new BusinessException("INVALID_DASHBOARD_DEFINITION", message);
    }

    private record RegisteredExtension(
            DashboardWidgetExtension extension,
            Set<String> allowedConfigFields,
            boolean configRequired
    ) {}
}
