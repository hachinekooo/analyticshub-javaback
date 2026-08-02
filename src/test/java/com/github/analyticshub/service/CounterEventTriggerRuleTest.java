package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BinaryNode;
import tools.jackson.databind.node.ObjectNode;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CounterEventTriggerRuleTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void legacyShapesRemainValidAndAreNormalizedIntoTypedClauses() throws Exception {
        CounterEventTriggerRule single = CounterEventTriggerRule.parse(objectMapper.readTree("""
                {
                  "semantic_key": " task_completed ",
                  "conditions": {}
                }
                """));
        CounterEventTriggerRule multiple = CounterEventTriggerRule.parse(objectMapper.readTree("""
                {
                  "semantic_keys": ["task_completed", "task_done_v2"],
                  "conditions": {"status": "success"}
                }
                """));

        assertThat(single.normalizedJson()).isEqualTo(
                objectMapper.readTree("{\"semantic_key\":\"task_completed\"}")
        );
        assertThat(single.clauses()).extracting(CounterEventTriggerRule.Clause::semanticKey)
                .containsExactly("task_completed");
        assertThat(multiple.clauses()).extracting(CounterEventTriggerRule.Clause::semanticKey)
                .containsExactly("task_completed", "task_done_v2");
        assertThat(multiple.clauses()).allSatisfy(
                clause -> assertThat(clause.conditions().path("status").asString())
                        .isEqualTo("success")
        );
    }

    @Test
    void anyOfNormalizesEveryClauseWithoutLosingIndependentConditions() throws Exception {
        CounterEventTriggerRule rule = CounterEventTriggerRule.parse(objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": " task_completed ", "conditions": {}},
                    {
                      "semantic_key": "task_done_v2",
                      "conditions": {
                        "status": "success",
                        "attempt": 1,
                        "verified": true,
                        "source": null,
                        "tags": ["stable"],
                        "context": {"channel": "api"}
                      }
                    }
                  ]
                }
                """));

        assertThat(rule.normalizedJson()).isEqualTo(objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": "task_completed"},
                    {
                      "semantic_key": "task_done_v2",
                      "conditions": {
                        "status": "success",
                        "attempt": 1,
                        "verified": true,
                        "source": null,
                        "tags": ["stable"],
                        "context": {"channel": "api"}
                      }
                    }
                  ]
                }
                """));
    }

    @Test
    void selectorModesAndClauseFieldsAreStrictlyAllowListed() throws Exception {
        assertInvalid("{}", "semantic_key、semantic_keys 或 any_of");
        assertInvalid(
                "{\"semantic_key\":\"task_completed\",\"semantic_keys\":[\"task_completed\"]}",
                "semantic_key、semantic_keys 或 any_of"
        );
        assertInvalid(
                "{\"semantic_key\":\"task_completed\",\"any_of\":[{\"semantic_key\":\"task_done\"}]}",
                "不能与"
        );
        assertInvalid("{\"any_of\":[]}", "1 到 100");
        assertInvalid("{\"any_of\":[\"task_completed\"]}", "必须是 JSON object");
        assertInvalid("{\"any_of\":[{}]}", "semantic_key 为必填");
        assertInvalid(
                "{\"any_of\":[{\"semantic_key\":\"task_completed\",\"increment_by\":2}]}",
                "不支持的字段"
        );
        assertInvalid(
                "{\"any_of\":[{\"semantic_key\":\"task_completed\",\"conditions\":[]}]}",
                "必须是 JSON object"
        );
    }

    @Test
    void clausesAndConditionContainersHaveBoundedSizes() {
        ObjectNode tooManyClauses = objectMapper.createObjectNode();
        var clauses = tooManyClauses.putArray("any_of");
        for (int index = 0; index <= CounterEventTriggerRule.MAX_CLAUSES; index++) {
            clauses.addObject().put("semantic_key", "event_" + index);
        }
        assertInvalid(tooManyClauses, "1 到 100");

        ObjectNode tooManyFields = oneClauseTrigger();
        ObjectNode conditions = (ObjectNode) tooManyFields.path("any_of").get(0).path("conditions");
        for (int index = 0; index <= CounterEventTriggerRule.MAX_CONDITION_CONTAINER_SIZE; index++) {
            conditions.put("field_" + index, index);
        }
        assertInvalid(tooManyFields, "最多包含 100 个字段");

        ObjectNode tooManyArrayItems = oneClauseTrigger();
        var array = ((ObjectNode) tooManyArrayItems.path("any_of").get(0).path("conditions"))
                .putArray("tags");
        for (int index = 0; index <= CounterEventTriggerRule.MAX_CONDITION_CONTAINER_SIZE; index++) {
            array.add(index);
        }
        assertInvalid(tooManyArrayItems, "最多包含 100 个元素");
    }

    @Test
    void conditionDepthAndTotalNodeBudgetAreBounded() {
        ObjectNode tooDeep = oneClauseTrigger();
        ObjectNode cursor = (ObjectNode) tooDeep.path("any_of").get(0).path("conditions");
        for (int index = 0; index < CounterEventTriggerRule.MAX_CONDITION_DEPTH; index++) {
            cursor = cursor.putObject("level_" + index);
        }
        assertInvalid(tooDeep, "嵌套深度不能超过 8");

        ObjectNode tooManyNodes = objectMapper.createObjectNode();
        var clauses = tooManyNodes.putArray("any_of");
        for (int clauseIndex = 0; clauseIndex < 10; clauseIndex++) {
            ObjectNode conditions = clauses.addObject()
                    .put("semantic_key", "event_" + clauseIndex)
                    .putObject("conditions");
            var values = conditions.putArray("values");
            for (int valueIndex = 0; valueIndex < 100; valueIndex++) {
                values.add(valueIndex);
            }
        }
        assertInvalid(tooManyNodes, "总节点数不能超过 1000");
    }

    @Test
    void conditionKeysTextAndNodeTypesAreBoundedToJsonSafeValues() {
        ObjectNode blankKey = oneClauseTrigger();
        ((ObjectNode) blankKey.path("any_of").get(0).path("conditions")).put(" ", "value");
        assertInvalid(blankKey, "字段名长度必须为 1 到 100");

        ObjectNode longKey = oneClauseTrigger();
        ((ObjectNode) longKey.path("any_of").get(0).path("conditions"))
                .put("k".repeat(CounterEventTriggerRule.MAX_CONDITION_KEY_LENGTH + 1), "value");
        assertInvalid(longKey, "字段名长度必须为 1 到 100");

        ObjectNode longText = oneClauseTrigger();
        ((ObjectNode) longText.path("any_of").get(0).path("conditions"))
                .put("message", "v".repeat(CounterEventTriggerRule.MAX_CONDITION_TEXT_LENGTH + 1));
        assertInvalid(longText, "字符串长度不能超过 1024");

        ObjectNode binaryValue = oneClauseTrigger();
        ((ObjectNode) binaryValue.path("any_of").get(0).path("conditions"))
                .set("payload", BinaryNode.valueOf(new byte[]{1, 2, 3}));
        assertInvalid(binaryValue, "仅支持 JSON");
    }

    @Test
    void exactDuplicateClausesAreRejectedButSameEventWithDifferentConditionsIsAllowed()
            throws Exception {
        assertInvalid("""
                {
                  "any_of": [
                    {
                      "semantic_key": "task_completed",
                      "conditions": {"source": "api", "status": "success"}
                    },
                    {
                      "semantic_key": "task_completed",
                      "conditions": {"status": "success", "source": "api"}
                    }
                  ]
                }
                """, "重复 clause");

        CounterEventTriggerRule rule = CounterEventTriggerRule.parse(objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": "task_completed", "conditions": {"source": "api"}},
                    {"semantic_key": "task_completed", "conditions": {"source": "import"}}
                  ]
                }
                """));
        assertThat(rule.clauses()).hasSize(2);
    }

    private ObjectNode oneClauseTrigger() {
        ObjectNode trigger = objectMapper.createObjectNode();
        trigger.putArray("any_of")
                .addObject()
                .put("semantic_key", "task_completed")
                .putObject("conditions");
        return trigger;
    }

    private void assertInvalid(String json, String messageFragment) throws Exception {
        assertInvalid(objectMapper.readTree(json), messageFragment);
    }

    private void assertInvalid(JsonNode trigger, String messageFragment) {
        assertThatThrownBy(() -> CounterEventTriggerRule.parse(trigger))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("INVALID_COUNTER_EVENT_TRIGGER");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains(messageFragment);
                });
    }
}
