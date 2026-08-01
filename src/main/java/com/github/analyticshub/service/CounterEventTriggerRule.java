package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, allow-listed representation of a Counter event trigger.
 *
 * <p>The persisted API remains JSON for backward compatibility. Parsing it
 * into clauses here gives realtime projection and historical rebuild one
 * canonical rule model, so the two paths cannot drift apart.</p>
 */
final class CounterEventTriggerRule {

    static final int MAX_CLAUSES = 100;
    static final int MAX_CONDITION_DEPTH = 8;
    static final int MAX_CONDITION_NODES = 1_000;
    static final int MAX_CONDITION_CONTAINER_SIZE = 100;
    static final int MAX_CONDITION_KEY_LENGTH = 100;
    static final int MAX_CONDITION_TEXT_LENGTH = 1_024;

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "event_type", "event_types", "conditions", "any_of"
    );
    private static final Set<String> CLAUSE_FIELDS = Set.of("event_type", "conditions");

    private final ObjectNode normalizedJson;
    private final List<Clause> clauses;

    private CounterEventTriggerRule(ObjectNode normalizedJson, List<Clause> clauses) {
        this.normalizedJson = normalizedJson;
        this.clauses = List.copyOf(clauses);
    }

    static CounterEventTriggerRule parse(JsonNode trigger) {
        if (trigger == null || !trigger.isObject()) {
            throw invalid("eventTrigger 必须是 JSON object");
        }
        requireOnlyFields(trigger, TOP_LEVEL_FIELDS, "eventTrigger");

        boolean hasAnyOf = trigger.has("any_of");
        boolean hasSingleEventType = trigger.has("event_type");
        boolean hasEventTypes = trigger.has("event_types");
        boolean hasTopLevelConditions = trigger.has("conditions");
        ConditionBudget conditionBudget = new ConditionBudget();

        if (hasAnyOf) {
            if (hasSingleEventType || hasEventTypes || hasTopLevelConditions) {
                throw invalid(
                        "eventTrigger.any_of 不能与 event_type、event_types 或顶层 conditions 混用"
                );
            }
            return parseAnyOf(trigger.get("any_of"), conditionBudget);
        }

        if (hasSingleEventType == hasEventTypes) {
            throw invalid("eventTrigger 必须且只能配置 event_type、event_types 或 any_of");
        }
        return parseLegacy(trigger, hasSingleEventType, conditionBudget);
    }

    JsonNode normalizedJson() {
        return normalizedJson;
    }

    List<Clause> clauses() {
        return clauses;
    }

    boolean matches(String eventType, JsonNode properties) {
        for (Clause clause : clauses) {
            if (!clause.eventType().equals(eventType)) {
                continue;
            }
            if (clause.conditions() == null) {
                return true;
            }
            if (properties != null && jsonContains(properties, clause.conditions())) {
                return true;
            }
        }
        return false;
    }

    private static CounterEventTriggerRule parseAnyOf(
            JsonNode anyOf,
            ConditionBudget conditionBudget
    ) {
        if (anyOf == null || !anyOf.isArray()
                || anyOf.isEmpty() || anyOf.size() > MAX_CLAUSES) {
            throw invalid("eventTrigger.any_of 必须包含 1 到 100 个 clause");
        }

        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        var normalizedClauses = normalized.putArray("any_of");
        List<Clause> clauses = new ArrayList<>(anyOf.size());
        Set<JsonNode> uniqueClauses = new HashSet<>();

        for (int index = 0; index < anyOf.size(); index++) {
            JsonNode rawClause = anyOf.get(index);
            String path = "eventTrigger.any_of[" + index + "]";
            if (rawClause == null || !rawClause.isObject()) {
                throw invalid(path + " 必须是 JSON object");
            }
            requireOnlyFields(rawClause, CLAUSE_FIELDS, path);
            if (!rawClause.has("event_type")) {
                throw invalid(path + ".event_type 为必填非空字符串");
            }

            String eventType = requireEventType(rawClause.get("event_type"), path + ".event_type");
            JsonNode conditions = normalizeConditions(
                    rawClause.get("conditions"),
                    rawClause.has("conditions"),
                    path + ".conditions",
                    conditionBudget
            );

            ObjectNode normalizedClause = normalizedClauses.addObject();
            normalizedClause.put("event_type", eventType);
            if (conditions != null) {
                normalizedClause.set("conditions", conditions);
            }
            if (!uniqueClauses.add(normalizedClause)) {
                throw invalid("eventTrigger.any_of 不能包含重复 clause");
            }
            clauses.add(new Clause(eventType, conditions));
        }
        return new CounterEventTriggerRule(normalized, clauses);
    }

    private static CounterEventTriggerRule parseLegacy(
            JsonNode trigger,
            boolean hasSingleEventType,
            ConditionBudget conditionBudget
    ) {
        JsonNode conditions = normalizeConditions(
                trigger.get("conditions"),
                trigger.has("conditions"),
                "eventTrigger.conditions",
                conditionBudget
        );
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        List<Clause> clauses = new ArrayList<>();

        if (hasSingleEventType) {
            String eventType = requireEventType(trigger.get("event_type"), "eventTrigger.event_type");
            normalized.put("event_type", eventType);
            clauses.add(new Clause(eventType, conditions));
        } else {
            JsonNode eventTypes = trigger.get("event_types");
            if (eventTypes == null || !eventTypes.isArray()
                    || eventTypes.isEmpty() || eventTypes.size() > MAX_CLAUSES) {
                throw invalid("eventTrigger.event_types 必须包含 1 到 100 个事件 key");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (int index = 0; index < eventTypes.size(); index++) {
                String eventType = requireEventType(
                        eventTypes.get(index),
                        "eventTrigger.event_types[" + index + "]"
                );
                if (!unique.add(eventType)) {
                    throw invalid("eventTrigger.event_types 不能包含重复 key");
                }
            }
            var normalizedEventTypes = normalized.putArray("event_types");
            unique.forEach(eventType -> {
                normalizedEventTypes.add(eventType);
                clauses.add(new Clause(eventType, conditions));
            });
        }
        if (conditions != null) {
            normalized.set("conditions", conditions);
        }
        return new CounterEventTriggerRule(normalized, clauses);
    }

    private static JsonNode normalizeConditions(
            JsonNode conditions,
            boolean present,
            String path,
            ConditionBudget conditionBudget
    ) {
        if (!present) {
            return null;
        }
        if (conditions == null || !conditions.isObject()) {
            throw invalid(path + " 必须是 JSON object");
        }
        validateConditionValue(conditions, path, 1, conditionBudget);
        return conditions.isEmpty() ? null : conditions.deepCopy();
    }

    private static void validateConditionValue(
            JsonNode value,
            String path,
            int depth,
            ConditionBudget conditionBudget
    ) {
        if (depth > MAX_CONDITION_DEPTH) {
            throw invalid(path + " 嵌套深度不能超过 " + MAX_CONDITION_DEPTH);
        }
        conditionBudget.addNode();

        if (value.isObject()) {
            if (value.size() > MAX_CONDITION_CONTAINER_SIZE) {
                throw invalid(path + " 每个 object 最多包含 100 个字段");
            }
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                String key = field.getKey();
                if (key == null || key.isBlank() || key.length() > MAX_CONDITION_KEY_LENGTH) {
                    throw invalid(path + " 的字段名长度必须为 1 到 100");
                }
                validateConditionValue(
                        field.getValue(),
                        path + "." + key,
                        depth + 1,
                        conditionBudget
                );
            }
            return;
        }
        if (value.isArray()) {
            if (value.size() > MAX_CONDITION_CONTAINER_SIZE) {
                throw invalid(path + " 每个 array 最多包含 100 个元素");
            }
            for (int index = 0; index < value.size(); index++) {
                validateConditionValue(
                        value.get(index),
                        path + "[" + index + "]",
                        depth + 1,
                        conditionBudget
                );
            }
            return;
        }
        if (value.isString()) {
            if (value.stringValue().length() > MAX_CONDITION_TEXT_LENGTH) {
                throw invalid(path + " 的字符串长度不能超过 1024");
            }
            return;
        }
        if (value.isNumber() || value.isBoolean() || value.isNull()) {
            return;
        }
        throw invalid(path + " 仅支持 JSON object、array、string、number、boolean 或 null");
    }

    private static boolean jsonContains(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> field : expected.properties()) {
                if (!actual.has(field.getKey())
                        || !jsonContains(actual.get(field.getKey()), field.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray()) {
                return false;
            }
            for (JsonNode expectedElement : expected) {
                boolean matched = false;
                for (JsonNode actualElement : actual) {
                    if (jsonContains(actualElement, expectedElement)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected);
    }

    private static String requireEventType(JsonNode value, String path) {
        if (value == null || !value.isString()) {
            throw invalid(path + " 必须是非空字符串");
        }
        String normalized = value.asString().strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw invalid(path + " 长度必须为 1 到 100");
        }
        return normalized;
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!allowed.contains(field.getKey())) {
                throw invalid(path + " 包含不支持的字段: " + field.getKey());
            }
        }
    }

    static BusinessException invalid(String message) {
        return new BusinessException(
                "INVALID_COUNTER_EVENT_TRIGGER",
                message,
                HttpStatus.BAD_REQUEST
        );
    }

    record Clause(String eventType, JsonNode conditions) {}

    private static final class ConditionBudget {
        private int nodes;

        void addNode() {
            nodes++;
            if (nodes > MAX_CONDITION_NODES) {
                throw invalid("eventTrigger.conditions 总节点数不能超过 " + MAX_CONDITION_NODES);
            }
        }
    }
}
