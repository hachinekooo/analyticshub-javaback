package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricTopResponse;
import com.github.analyticshub.dto.TrafficMetricTrendResponse;
import com.github.analyticshub.service.TrafficMetricStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTrafficMetricControllerTest {

    private AdminTrafficMetricController controller;

    @Mock
    private TrafficMetricStatsService trafficMetricStatsService;

    @BeforeEach
    void setUp() {
        controller = new AdminTrafficMetricController(null, trafficMetricStatsService);
    }

    @Test
    void testTrends() {
        TrafficMetricTrendResponse response = new TrafficMetricTrendResponse("p-123", null, null, "day", List.of());
        when(trafficMetricStatsService.getTrends(eq("p-123"), any(), any(), any())).thenReturn(response);

        ApiResponse<TrafficMetricTrendResponse> apiResponse = controller.trends("p-123", null, null, "day");

        assertNotNull(apiResponse);
        verify(trafficMetricStatsService).getTrends(eq("p-123"), any(), any(), any());
    }

    @Test
    void testTopPages() {
        TrafficMetricTopResponse response = new TrafficMetricTopResponse("p-123", null, null, List.of());
        when(trafficMetricStatsService.getTopPages(eq("p-123"), any(), any(), any())).thenReturn(response);

        ApiResponse<TrafficMetricTopResponse> apiResponse = controller.topPages("p-123", null, null, 10);

        assertNotNull(apiResponse);
        verify(trafficMetricStatsService).getTopPages(eq("p-123"), any(), any(), any());
    }
}
