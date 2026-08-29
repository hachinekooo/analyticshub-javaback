package com.github.analyticshub.controller;

import com.github.analyticshub.dto.EventBatchTrackSummary;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.exception.GlobalExceptionHandler;
import com.github.analyticshub.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    @Mock
    private EventService eventService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EventController(eventService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void batchAddsBoundedProcessingSummaryWithoutChangingCreatedStatus() throws Exception {
        when(eventService.trackEventsBatch(any())).thenReturn(new EventBatchTrackSummary(
                3, 2, 1, 1, 1, Map.of("INVALID_EVENT_PROPERTIES", 1)
        ));

        mockMvc.perform(post("/api/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"eventType":"action_one","timestamp":1,"properties":{}},
                                  {"eventType":"action_two","timestamp":2,"properties":{}},
                                  {"eventType":"action_three","timestamp":3,"properties":{}}
                                ]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.receivedCount").value(3))
                .andExpect(jsonPath("$.data.acceptedCount").value(2))
                .andExpect(jsonPath("$.data.insertedCount").value(1))
                .andExpect(jsonPath("$.data.duplicateCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.rejectionCounts.INVALID_EVENT_PROPERTIES").value(1));
    }

    @Test
    void mixedValidityBatchReachesBestEffortServiceInsteadOfFailingAtControllerValidation() throws Exception {
        when(eventService.trackEventsBatch(any())).thenReturn(new EventBatchTrackSummary(
                2, 1, 1, 0, 1, Map.of("MISSING_EVENT_TYPE", 1)
        ));

        mockMvc.perform(post("/api/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"eventType":"valid_action","timestamp":1,"properties":{}},
                                  {"eventType":"","timestamp":2,"properties":{}}
                                ]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.rejectionCounts.MISSING_EVENT_TYPE").value(1));

        verify(eventService).trackEventsBatch(any());
    }

    @Test
    void singleStrictRejectionKeepsTheDocumentedHttpStatusAndErrorCode() throws Exception {
        when(eventService.trackEvent(any()))
                .thenThrow(BusinessException.eventPropertiesTooLarge(32 * 1024));

        mockMvc.perform(post("/api/v1/events/track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"action","timestamp":1,"properties":{}}
                                """))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EVENT_PROPERTIES_TOO_LARGE"));
    }
}
