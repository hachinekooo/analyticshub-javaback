package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.TrafficMetricService;
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
                "",
                "app.example.com"
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
        assertNull(assignedCookie.getDomain());
        assertEquals(15552000, assignedCookie.getMaxAge());
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
    void trackSanitizesHeaderRefererAndDoesNotPersistRawClientMetadata() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("Host")).thenReturn("app.example.com");
        lenient().when(request.getHeader("Referer")).thenReturn("https://app.example.com/zh?email=secret#section");
        lenient().when(request.getHeader("User-Agent")).thenReturn("Example Browser/1.0");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-sanitized"));

        controller.track(
                new TrafficMetricTrackRequest("page_view", "/zh?token=secret#section", null, 1700000000000L, null, null),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals("/zh", requestCaptor.getValue().pagePath());
        assertEquals("/zh", requestCaptor.getValue().referrer());
    }

    @Test
    void trackKeepsOnlyExternalReferrerHost() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-external"));

        controller.track(
                new TrafficMetricTrackRequest(
                        "page_view",
                        "/zh",
                        "https://search.example/results?q=private",
                        1700000000000L,
                        null,
                        tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("userAgent", "spoofed")
                                .put("ipHash", "spoofed")
                ),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals("search.example", requestCaptor.getValue().referrer());
        assertNull(requestCaptor.getValue().metadata());
    }

    @Test
    void trackSanitizesSchemeRelativeReferrerAndIgnoresForwardedHostSpoofing() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("Host")).thenReturn("app.example.com");
        lenient().when(request.getHeader("X-Forwarded-Host")).thenReturn("external.example");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-scheme-relative"));

        controller.track(
                new TrafficMetricTrackRequest(
                        "page_view",
                        "/zh",
                        "//external.example/private/user?token=secret",
                        1700000000000L,
                        null,
                        null
                ),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals("external.example", requestCaptor.getValue().referrer());
    }

    @Test
    void trackDoesNotUseForwardedHostToClassifyExternalReferrerAsInternal() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("Host")).thenReturn("app.example.com");
        lenient().when(request.getHeader("X-Forwarded-Host")).thenReturn("external.example");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-forwarded-host"));

        controller.track(
                new TrafficMetricTrackRequest(
                        "page_view",
                        "/zh",
                        "https://external.example/private/user?token=secret",
                        1700000000000L,
                        null,
                        null
                ),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals("external.example", requestCaptor.getValue().referrer());
    }

    @Test
    void trackDoesNotTrustRequestHostWhenClassifyingReferrerOrigin() {
        controller = new PublicTrafficController(trafficMetricService, trafficMetricStatsService, "", "");
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("Host")).thenReturn("external.example");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-host-spoof"));

        controller.track(
                new TrafficMetricTrackRequest(
                        "page_view", "/zh", "https://external.example/private/user?token=secret",
                        1700000000000L, null, null
                ),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals("external.example", requestCaptor.getValue().referrer());
    }

    @Test
    void botRequestKeepsOnlyBotFlagInPublicMetadata() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("User-Agent")).thenReturn("ExampleCrawler/1.0");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-bot"));

        controller.track(
                new TrafficMetricTrackRequest(
                        "page_view",
                        "/zh",
                        null,
                        1700000000000L,
                        null,
                        tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("ipHash", "spoofed")
                ),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<TrafficMetricTrackRequest> requestCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest.class);
        verify(trafficMetricService).trackPublic(
                eq("p-123"), any(UUID.class), any(), requestCaptor.capture(), isNull(), isNull());
        assertEquals(1, requestCaptor.getValue().metadata().size());
        assertTrue(requestCaptor.getValue().metadata().path("isBot").asBoolean());
        assertFalse(requestCaptor.getValue().metadata().has("ipHash"));
    }

    @Test
    void clearTrafficIdentityExpiresHostOnlyCookie() {
        when(request.isSecure()).thenReturn(true);

        controller.clearTrafficIdentity(request, response);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());
        Cookie expiredCookie = cookieCaptor.getValue();
        assertEquals("ah_did", expiredCookie.getName());
        assertEquals("", expiredCookie.getValue());
        assertEquals("/", expiredCookie.getPath());
        assertNull(expiredCookie.getDomain());
        assertEquals(0, expiredCookie.getMaxAge());
        assertTrue(expiredCookie.isHttpOnly());
        assertTrue(expiredCookie.getSecure());
        assertEquals("Lax", expiredCookie.getAttribute("SameSite"));
    }

    @Test
    void assignedCookieIsSecureBehindHttpsProxy() {
        when(request.getHeader("X-Project-ID")).thenReturn("p-123");
        lenient().when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(trafficMetricService.trackPublic(eq("p-123"), any(UUID.class), any(), any(), isNull(), isNull()))
                .thenReturn(new TrafficMetricTrackResponse("m-secure"));

        controller.track(
                new TrafficMetricTrackRequest("page_view", "/", null, 1700000000000L, null, null),
                null,
                null,
                request,
                response
        );

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());
        assertTrue(cookieCaptor.getValue().getSecure());
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
                eq("p-123"), eq(headerId), any(), any(TrafficMetricTrackRequest[].class), isNull(), isNull()))
                .thenReturn(1);

        ApiResponse<java.util.Map<String, Integer>> apiResponse = controller.batch(
                new TrafficMetricTrackRequest[]{
                        new TrafficMetricTrackRequest(
                                "page_view",
                                "/?secret=value",
                                null,
                                1700000000000L,
                                null,
                                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("ipHash", "spoofed")
                        )
                },
                null,
                null,
                request,
                response
        );

        assertEquals(1, apiResponse.data().get("accepted"));
        ArgumentCaptor<TrafficMetricTrackRequest[]> itemsCaptor = ArgumentCaptor.forClass(TrafficMetricTrackRequest[].class);
        verify(trafficMetricService).trackPublicBatch(
                eq("p-123"), eq(headerId), any(), itemsCaptor.capture(), isNull(), isNull());
        assertEquals("/", itemsCaptor.getValue()[0].pagePath());
        assertNull(itemsCaptor.getValue()[0].metadata());
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
