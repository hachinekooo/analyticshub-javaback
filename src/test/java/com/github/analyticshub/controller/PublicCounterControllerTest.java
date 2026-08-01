package com.github.analyticshub.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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

    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        controller = new PublicCounterController(counterService);
    }

    @Test
    void testGet_I18n_Zh() throws Exception {
        String projectId = "demo_project";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计完成任务\", \"en\": \"Completed Tasks\"}");
        JsonNode unit = objectMapper.readTree("{\"zh\": \"项\", \"en\": \"Tasks\"}");
        
        CounterRecord record = new CounterRecord(
                "tasks_completed", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("tasks_completed"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "tasks_completed", request);
        
        assertNotNull(response.data());
        assertEquals("累计完成任务", response.data().displayName());
        assertEquals("项", response.data().unit());
    }

    @Test
    void testGet_I18n_En() throws Exception {
        String projectId = "demo_project";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计完成任务\", \"en\": \"Completed Tasks\"}");
        JsonNode unit = objectMapper.readTree("{\"zh\": \"项\", \"en\": \"Tasks\"}");
        
        CounterRecord record = new CounterRecord(
                "tasks_completed", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("tasks_completed"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US,en;q=0.8");
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "tasks_completed", request);
        
        assertNotNull(response.data());
        assertEquals("Completed Tasks", response.data().displayName());
        assertEquals("Tasks", response.data().unit());
    }

    @Test
    void testGet_I18n_PreservesFullTagAndMatchesCaseInsensitively() throws Exception {
        String projectId = "demo_project";
        JsonNode displayName = objectMapper.readTree(
                "{\"ZH-cn\": \"累计完成信件\", \"en\": \"Completed Letters\"}"
        );
        JsonNode unit = objectMapper.readTree("{\"zh-CN\": \"封\", \"en\": \"Letters\"}");
        CounterRecord record = new CounterRecord(
                "letters_completed", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        when(counterService.get(eq(projectId), eq("letters_completed"), anyBoolean())).thenReturn(record);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US;q=0.4, zh-CN;q=0.9");

        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "letters_completed", request);

        assertNotNull(response.data());
        assertEquals("累计完成信件", response.data().displayName());
        assertEquals("封", response.data().unit());
    }

    @Test
    void testGet_I18n_MatchesRequestedBaseToAvailableVariant() throws Exception {
        String projectId = "demo_project";
        JsonNode displayName = objectMapper.readTree(
                "{\"zh-CN\": \"累计完成信件\", \"en\": \"Completed Letters\"}"
        );
        CounterRecord record = new CounterRecord(
                "letters_completed", 100, displayName, null, null, true, "desc", "2026-01-01"
        );
        when(counterService.get(eq(projectId), eq("letters_completed"), anyBoolean())).thenReturn(record);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-Hans-CN, en;q=0.5");

        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "letters_completed", request);

        assertNotNull(response.data());
        assertEquals("累计完成信件", response.data().displayName());
    }

    @Test
    void testGet_I18n_Fallback() throws Exception {
        String projectId = "demo_project";
        JsonNode displayName = objectMapper.readTree("{\"zh\": \"累计完成任务\"}"); // Only zh
        JsonNode unit = objectMapper.readTree("{\"zh\": \"封\"}");
        
        CounterRecord record = new CounterRecord(
                "tasks_completed", 100, displayName, unit, null, true, "desc", "2026-01-01"
        );
        
        when(counterService.get(eq(projectId), eq("tasks_completed"), anyBoolean())).thenReturn(record);
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ja-JP"); // Japanese, should fallback to zh
        
        ApiResponse<PublicCounterResponse> response = controller.get(projectId, "tasks_completed", request);
        
        assertNotNull(response.data());
        assertEquals("累计完成任务", response.data().displayName());
    }
}
