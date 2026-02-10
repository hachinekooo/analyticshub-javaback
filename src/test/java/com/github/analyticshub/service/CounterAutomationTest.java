package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
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

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        counterService = new CounterService(dataSourceManager, objectMapper);
    }

    @Test
    void testIsMatch_Basic() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"event_type\": \"send_letter\"}");
        
        assertTrue(counterService.isMatch(trigger, "send_letter", null));
        assertFalse(counterService.isMatch(trigger, "other_event", null));
    }

    @Test
    void testIsMatch_WithConditions() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"event_type\": \"send_letter\", \"conditions\": {\"status\": \"success\"}}");
        
        // Match: status is success
        assertTrue(counterService.isMatch(trigger, "send_letter", Map.of("status", "success")));
        
        // No match: status is failed
        assertFalse(counterService.isMatch(trigger, "send_letter", Map.of("status", "failed")));
        
        // No match: status missing
        assertFalse(counterService.isMatch(trigger, "send_letter", Map.of("other", "val")));
        
        // No match: properties null
        assertFalse(counterService.isMatch(trigger, "send_letter", null));
    }

    @Test
    void testIsMatch_MultipleConditions() throws Exception {
        JsonNode trigger = objectMapper.readTree("{\"event_type\": \"send_letter\", \"conditions\": {\"status\": \"success\", \"type\": \"quick\"}}");
        
        assertTrue(counterService.isMatch(trigger, "send_letter", Map.of("status", "success", "type", "quick")));
        assertFalse(counterService.isMatch(trigger, "send_letter", Map.of("status", "success", "type", "slow")));
    }
}
