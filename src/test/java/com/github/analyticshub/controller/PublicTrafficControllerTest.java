package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.service.TrafficMetricService;
import com.github.analyticshub.service.TrafficMetricStatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicTrafficControllerTest {

    private PublicTrafficController controller;

    @Mock
    private TrafficMetricService trafficMetricService;

    @Mock
    private TrafficMetricStatsService trafficMetricStatsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        controller = new PublicTrafficController(trafficMetricService, trafficMetricStatsService, "");
    }

    @Test
    void testTrack() {
        TrafficMetricTrackRequest trackRequest = new TrafficMetricTrackRequest(
                "page_view", "/", null, 1700000000000L, null, null
        );
        TrafficMetricTrackResponse trackResponse = new TrafficMetricTrackResponse("m-123");

        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), any(), any()))
                .thenReturn(trackResponse);

        ApiResponse<TrafficMetricTrackResponse> apiResponse = controller.track(trackRequest, null, null, request, response);

        assertNotNull(apiResponse);
        assertEquals("m-123", apiResponse.data().metricId());
        verify(trafficMetricService).trackPublic(eq("p-123"), any(UUID.class), any(), any(), any(), any());
    }

    @Test
    void testTrackWithExistingCookie() {
        UUID existingId = UUID.randomUUID();
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("ah_did", existingId.toString());
        when(request.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{cookie});
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        
        TrafficMetricTrackRequest trackRequest = new TrafficMetricTrackRequest("page_view", "/", null, 1700000000000L, null, null);
        when(trafficMetricService.trackPublic(eq("p-123"), eq(existingId), any(), any(), any(), any()))
                .thenReturn(new TrafficMetricTrackResponse("m-123"));

        controller.track(trackRequest, null, null, request, response);

        verify(trafficMetricService).trackPublic(eq("p-123"), eq(existingId), any(), any(), any(), any());
    }

    @Test
    void testSummary() {
        TrafficMetricSummaryResponse summaryResponse = new TrafficMetricSummaryResponse("p-123", null, null, 10, 5);
        when(trafficMetricStatsService.getSummary(eq("p-123"), any(), any())).thenReturn(summaryResponse);

        ApiResponse<TrafficMetricSummaryResponse> apiResponse = controller.getPublicSummary("p-123", null, null, request);

        assertNotNull(apiResponse);
        assertEquals(10, apiResponse.data().pageViews());
        assertEquals(5, apiResponse.data().visitors());
    }
}
