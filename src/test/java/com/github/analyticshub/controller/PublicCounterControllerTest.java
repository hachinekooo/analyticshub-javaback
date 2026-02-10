package com.github.analyticshub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.dto.PublicCounterResponse;
import com.github.analyticshub.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicCounterControllerTest {

    private PublicCounterController controller;

    @Mock
    private CounterService counterService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new PublicCounterController(counterService);
    }

    @Test
    void testGet_I18n_Zh() throws Exception {
        String projectId = "memobox";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计寄信\", \"en\": \"Total Letters\"}");
        JsonNode unit = objectMapper.readTree("{\"zh\": \"封\", \"en\": \"Letters\"}");
        
        CounterRecord record = new CounterRecord(
                "total_letters", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("total_letters"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "total_letters", request);
        
        assertNotNull(response.data());
        assertEquals("累计寄信", response.data().displayName());
        assertEquals("封", response.data().unit());
    }

    @Test
    void testGet_I18n_En() throws Exception {
        String projectId = "memobox";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计寄信\", \"en\": \"Total Letters\"}");
        JsonNode unit = objectMapper.readTree("{\"zh\": \"封\", \"en\": \"Letters\"}");
        
        CounterRecord record = new CounterRecord(
                "total_letters", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("total_letters"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US,en;q=0.8");
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "total_letters", request);
        
        assertNotNull(response.data());
        assertEquals("Total Letters", response.data().displayName());
        assertEquals("Letters", response.data().unit());
    }

    @Test
    void testGet_I18n_Fallback() throws Exception {
        String projectId = "memobox";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计寄信\"}"); // Only zh
        JsonNode unit = objectMapper.readTree("{\"zh\": \"封\"}");
        
        CounterRecord record = new CounterRecord(
                "total_letters", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("total_letters"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ja-JP"); // Japanese, should fallback to zh
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "total_letters", request);
        
        assertNotNull(response.data());
        assertEquals("累计寄信", response.data().displayName());
    }
}
