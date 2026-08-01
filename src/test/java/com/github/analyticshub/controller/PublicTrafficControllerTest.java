package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.TrafficMetricService;
import com.github.analyticshub.security.ClientIpResolver;
import com.github.analyticshub.service.TrafficMetricStatsService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        controller = new PublicTrafficController(
                trafficMetricService,
                trafficMetricStatsService,
                new ClientIpResolver(""),
                ""
        );
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
        ArgumentCaptor<UUID> deviceIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(trafficMetricService).trackPublic(eq("p-123"), deviceIdCaptor.capture(), any(), any(), any(), any());

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());
        Cookie assignedCookie = cookieCaptor.getValue();
        assertEquals("ah_did", assignedCookie.getName());
        assertEquals(deviceIdCaptor.getValue().toString(), assignedCookie.getValue());
        assertEquals("/", assignedCookie.getPath());
        assertEquals(31536000, assignedCookie.getMaxAge());
        assertTrue(assignedCookie.isHttpOnly());
        assertEquals("Lax", assignedCookie.getAttribute("SameSite"));
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
        verify(response, never()).addCookie(any(Cookie.class));
    }

    @Test
    void headerDeviceIdTakesPriorityOverCookie() {
        UUID headerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID cookieId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("X-Device-ID")).thenReturn(headerId.toString());
        lenient().when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("ah_did", cookieId.toString())});
        when(trafficMetricService.trackPublic(eq("p-123"), eq(headerId), any(), any(), any(), any()))
                .thenReturn(new TrafficMetricTrackResponse("m-header"));

        controller.track(
                new TrafficMetricTrackRequest("page_view", "/", null, 1700000000000L, null, null),
                null,
                null,
                request,
                response
        );

        verify(trafficMetricService).trackPublic(eq("p-123"), eq(headerId), any(), any(), any(), any());
        verify(response, never()).addCookie(any(Cookie.class));
    }

    @Test
    void invalidOrNonCanonicalHeaderReturnsBadRequestWithoutCookieFallback() {
        UUID cookieId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("X-Device-ID"))
                .thenReturn("550E8400-E29B-41D4-A716-446655440000");
        lenient().when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("ah_did", cookieId.toString())});

        BusinessException exception = assertThrows(BusinessException.class, () -> controller.track(
                new TrafficMetricTrackRequest("page_view", "/", null, 1700000000000L, null, null),
                null,
                null,
                request,
                response
        ));

        assertEquals("INVALID_DEVICE_ID", exception.getCode());
        assertEquals(400, exception.getHttpStatus().value());
        verifyNoInteractions(trafficMetricService);
        verify(response, never()).addCookie(any(Cookie.class));
    }

    @Test
    void batchAlsoUsesCanonicalHeaderDeviceId() {
        UUID headerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("X-Device-ID")).thenReturn(headerId.toString());
        when(trafficMetricService.trackPublicBatch(
                eq("p-123"), eq(headerId), any(), any(TrafficMetricTrackRequest[].class), any(), any()))
                .thenReturn(1);

        ApiResponse<java.util.Map<String, Integer>> apiResponse = controller.batch(
                new TrafficMetricTrackRequest[]{
                        new TrafficMetricTrackRequest("page_view", "/", null, 1700000000000L, null, null)
                },
                null,
                null,
                request,
                response
        );

        assertEquals(1, apiResponse.data().get("accepted"));
        verify(trafficMetricService).trackPublicBatch(
                eq("p-123"), eq(headerId), any(), any(TrafficMetricTrackRequest[].class), any(), any());
        verify(response, never()).addCookie(any(Cookie.class));
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
