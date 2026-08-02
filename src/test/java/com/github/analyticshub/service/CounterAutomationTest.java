package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CounterAutomationTest {

    private CounterService counterService;

    @Mock
    private MultiDataSourceManager dataSourceManager;

    @Mock
    private SemanticDictionaryService semanticDictionaryService;

    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        counterService = new CounterService(
                dataSourceManager,
                objectMapper,
                new ProjectTransactionExecutor(),
                semanticDictionaryService
        );
    }

    @Test
    void testIsMatch_Basic() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"semantic_key\": \"task_completed\"}");
        
        assertTrue(counterService.isMatch(trigger, "task_completed", null));
        assertFalse(counterService.isMatch(trigger, "other_event", null));
    }

    @Test
    void multipleHistoricalEventKeysCanFeedOneCounter() throws Exception {
        JsonNode trigger = objectMapper.readTree(
                "{\"semantic_keys\":[\"task_completed\",\"task_done_v2\"]}"
        );

        assertTrue(counterService.isMatch(trigger, "task_completed", null));
        assertTrue(counterService.isMatch(trigger, "task_done_v2", null));
        assertFalse(counterService.isMatch(trigger, "task_created", null));
    }

    @Test
    void anyOfAppliesConditionsToEachEventAliasIndependently() throws Exception {
        JsonNode trigger = objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": "task_completed"},
                    {
                      "semantic_key": "task_done_v2",
                      "conditions": {"status": "success"}
                    }
                  ]
                }
                """);

        assertTrue(counterService.isMatch(trigger, "task_completed", null));
        assertTrue(counterService.isMatch(
                trigger,
                "task_done_v2",
                Map.of("status", "success")
        ));
        assertFalse(counterService.isMatch(
                trigger,
                "task_done_v2",
                Map.of("status", "failed")
        ));
        assertFalse(counterService.isMatch(trigger, "task_created", Map.of()));
    }

    @Test
    void anyOfUsesOrSemanticsWhenTheSameEventHasMultipleConditionBranches() throws Exception {
        JsonNode trigger = objectMapper.readTree("""
                {
                  "any_of": [
                    {"semantic_key": "task_completed", "conditions": {"source": "api"}},
                    {"semantic_key": "task_completed", "conditions": {"source": "import"}}
                  ]
                }
                """);

        assertTrue(counterService.isMatch(
                trigger,
                "task_completed",
                Map.of("source", "import")
        ));
        assertFalse(counterService.isMatch(
                trigger,
                "task_completed",
                Map.of("source", "editor")
        ));
    }

    @Test
    void testIsMatch_WithConditions() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"semantic_key\": \"task_completed\", \"conditions\": {\"status\": \"success\"}}");
        
        // Match: status is success
        assertTrue(counterService.isMatch(trigger, "task_completed", Map.of("status", "success")));
        
        // No match: status is failed
        assertFalse(counterService.isMatch(trigger, "task_completed", Map.of("status", "failed")));
        
        // No match: status missing
        assertFalse(counterService.isMatch(trigger, "task_completed", Map.of("other", "val")));
        
        // No match: properties null
        assertFalse(counterService.isMatch(trigger, "task_completed", null));
    }

    @Test
    void testIsMatch_MultipleConditions() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"semantic_key\": \"task_completed\", \"conditions\": {\"status\": \"success\", \"type\": \"quick\"}}");
        
        assertTrue(counterService.isMatch(trigger, "task_completed", Map.of("status", "success", "type", "quick")));
        assertFalse(counterService.isMatch(trigger, "task_completed", Map.of("status", "success", "type", "slow")));
    }

    @Test
    void emptyConditionsBehaveLikeNoFilter() throws Exception {
        JsonNode trigger = objectMapper.readTree(
                "{\"semantic_key\":\"task_completed\",\"conditions\":{}}"
        );

        assertTrue(counterService.isMatch(trigger, "task_completed", null));
    }

    @Test
    void nestedConditionsUseJsonContainmentSemantics() throws Exception {
        JsonNode trigger = objectMapper.readTree("""
                {
                  "semantic_key": "task_completed",
                  "conditions": {
                    "score": 1.0,
                    "context": {"channel": "api"},
                    "tags": ["stable"]
                  }
                }
                """);

        assertTrue(counterService.isMatch(
                trigger,
                "task_completed",
                Map.of(
                        "score", 1,
                        "context", Map.of("channel", "api", "version", 2),
                        "tags", java.util.List.of("paid", "stable")
                )
        ));
    }

    @Test
    void malformedRuleIsIgnoredWithoutBreakingEventCollection() throws Exception {
        JsonNode trigger = objectMapper.readTree(
                "{\"semantic_key\":\"task_completed\",\"unsupported\":true}"
        );

        assertFalse(counterService.isMatch(trigger, "task_completed", Map.of()));
        assertFalse(counterService.isMatch(null, "task_completed", Map.of()));
    }

    @Test
    void counterKeysAreSafeStablePathIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> counterService.get("project", "bad/key", false));
        assertThrows(IllegalArgumentException.class,
                () -> counterService.get("project", "counter key", false));
    }
}
