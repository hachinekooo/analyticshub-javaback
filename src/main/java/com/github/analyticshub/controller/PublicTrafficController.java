package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.TrafficMetricService;
import com.github.analyticshub.util.CryptoUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.service.TrafficMetricStatsService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/traffic")
public class PublicTrafficController {

    private final TrafficMetricService trafficMetricService;
    private final TrafficMetricStatsService trafficMetricStatsService;
    private final String publicToken;

    public PublicTrafficController(TrafficMetricService trafficMetricService,
                                   TrafficMetricStatsService trafficMetricStatsService,
                                   @Value("${app.traffic.public-token:}") String publicToken) {
        this.trafficMetricService = trafficMetricService;
        this.trafficMetricStatsService = trafficMetricStatsService;
        this.publicToken = publicToken == null ? "" : publicToken;
    }

    @GetMapping("/summary")
    public ApiResponse<TrafficMetricSummaryResponse> getPublicSummary(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest httpServletRequest) {
        checkPublicToken(httpServletRequest);
        return ApiResponse.success(trafficMetricStatsService.getSummary(projectId, from, to));
    }

    @PostMapping("/track")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TrafficMetricTrackResponse> track(
            @Valid @RequestBody TrafficMetricTrackRequest request,
            @RequestParam(value = "projectId", required = false) String queryProjectId,
            @RequestParam(value = "userId", required = false) String queryUserId,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        checkPublicToken(httpServletRequest);

        String projectId = resolveProjectId(httpServletRequest, queryProjectId);
        String userId = resolveUserId(httpServletRequest, queryUserId);

        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException("MISSING_PROJECT_ID", "缺少项目ID");
        }
        UUID resolvedDeviceId = resolveOrAssignDeviceId(httpServletRequest, httpServletResponse);

        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");
        String referrer = resolveReferer(request, httpServletRequest);
        boolean bot = isBot(userAgent);

        // 如果是机器人，可以在 metadata 中记录
        TrafficMetricTrackRequest enrichedRequest = request;
        if (bot || (referrer != null && !referrer.equals(request.referrer()))) {
            com.fasterxml.jackson.databind.node.ObjectNode metadata = (request.metadata() != null && request.metadata().isObject())
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) request.metadata().deepCopy()
                    : new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            if (bot) metadata.put("isBot", true);
            if (referrer != null) metadata.put("resolvedReferrer", referrer);
            
            enrichedRequest = new TrafficMetricTrackRequest(
                    request.metricType(),
                    request.pagePath(),
                    referrer != null ? referrer : request.referrer(),
                    request.timestamp(),
                    request.sessionId(),
                    metadata
            );
        }

        return ApiResponse.success(
                trafficMetricService.trackPublic(projectId, resolvedDeviceId, userId, enrichedRequest, clientIp, userAgent)
        );
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Integer>> batch(
            @Valid @RequestBody TrafficMetricTrackRequest[] items,
            @RequestParam(value = "projectId", required = false) String queryProjectId,
            @RequestParam(value = "userId", required = false) String queryUserId,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        checkPublicToken(httpServletRequest);

        String projectId = resolveProjectId(httpServletRequest, queryProjectId);
        String userId = resolveUserId(httpServletRequest, queryUserId);

        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException("MISSING_PROJECT_ID", "缺少项目ID");
        }
        UUID resolvedDeviceId = resolveOrAssignDeviceId(httpServletRequest, httpServletResponse);

        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");
        boolean bot = isBot(userAgent);

        if (items == null || items.length == 0) {
            throw new BusinessException("EMPTY_ITEMS", "请求体不能为空");
        }

        // 预处理批量数据中的 Referer 和 Bot 标记
        TrafficMetricTrackRequest[] processedItems = new TrafficMetricTrackRequest[items.length];
        for (int i = 0; i < items.length; i++) {
            TrafficMetricTrackRequest item = items[i];
            String referrer = resolveReferer(item, httpServletRequest);
            if (bot || (referrer != null && !referrer.equals(item.referrer()))) {
                com.fasterxml.jackson.databind.node.ObjectNode metadata = (item.metadata() != null && item.metadata().isObject())
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) item.metadata().deepCopy()
                        : new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                if (bot) metadata.put("isBot", true);
                if (referrer != null) metadata.put("resolvedReferrer", referrer);
                
                processedItems[i] = new TrafficMetricTrackRequest(
                        item.metricType(),
                        item.pagePath(),
                        referrer != null ? referrer : item.referrer(),
                        item.timestamp(),
                        item.sessionId(),
                        metadata
                );
            } else {
                processedItems[i] = item;
            }
        }

        int accepted = trafficMetricService.trackPublicBatch(
                projectId,
                resolvedDeviceId,
                userId,
                processedItems,
                clientIp,
                userAgent
        );
        if (accepted == 0) {
            throw new BusinessException("NO_VALID_ITEMS", "批量请求中没有可写入的数据（请确认 metricType 字段）");
        }
        return ApiResponse.success(Map.of(
                "received", items.length,
                "accepted", accepted,
                "rejected", items.length - accepted
        ));
    }

    private void checkPublicToken(HttpServletRequest request) {
        if (publicToken.isBlank()) {
            return;
        }
        String token = request.getHeader("X-Traffic-Token");
        if (token == null || token.isBlank()) {
            throw new BusinessException("TRAFFIC_TOKEN_MISSING", "缺少流量采集 Token，请在请求头 X-Traffic-Token 传递", HttpStatus.UNAUTHORIZED);
        }
        if (!constantTimeEquals(publicToken, token)) {
            throw new BusinessException("TRAFFIC_TOKEN_INVALID", "无效的流量采集 Token", HttpStatus.UNAUTHORIZED);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int max = Math.max(x.length, y.length);

        int result = x.length ^ y.length;
        for (int i = 0; i < max; i++) {
            byte xb = i < x.length ? x[i] : 0;
            byte yb = i < y.length ? y[i] : 0;
            result |= xb ^ yb;
        }
        return result == 0;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String resolveReferer(TrafficMetricTrackRequest request, HttpServletRequest httpServletRequest) {
        String referrer = request.referrer();
        if (referrer != null && !referrer.isBlank()) {
            return referrer.trim();
        }
        String headerReferer = httpServletRequest.getHeader("Referer");
        return (headerReferer == null || headerReferer.isBlank()) ? null : headerReferer.trim();
    }

    private static boolean isBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
        return ua.contains("bot") || ua.contains("spider") || ua.contains("crawler") ||
                ua.contains("googlebot") || ua.contains("bingbot") || ua.contains("slurp") ||
                ua.contains("duckduckbot") || ua.contains("baiduspider") || ua.contains("yandexbot") ||
                ua.contains("sogou") || ua.contains("exabot") || ua.contains("facebot") ||
                ua.contains("ia_archiver");
    }

    private static UUID resolveOrAssignDeviceId(HttpServletRequest request, HttpServletResponse response) {
        // 完全依赖 Cookie 进行设备识别
        UUID cookieDeviceId = readDeviceIdCookie(request);
        if (cookieDeviceId != null) {
            return cookieDeviceId;
        }

        UUID assigned = UUID.randomUUID();
        Cookie cookie = new Cookie("ah_did", assigned.toString());
        cookie.setPath("/");
        cookie.setMaxAge(31536000);
        cookie.setHttpOnly(true);
        
        // 适配 Nginx 代理下的 HTTPS 识别
        boolean secure = request.isSecure();
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && forwardedProto.equalsIgnoreCase("https")) {
            secure = true;
        }
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return assigned;
    }

    private static String resolveProjectId(HttpServletRequest request, String queryId) {
        String headerId = request.getHeader("X-Project-ID");
        return (headerId != null && !headerId.isBlank()) ? headerId : queryId;
    }

    private static String resolveUserId(HttpServletRequest request, String queryId) {
        String headerId = request.getHeader("X-User-ID");
        return (headerId != null && !headerId.isBlank()) ? headerId : queryId;
    }

    private static UUID readDeviceIdCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie == null) {
                continue;
            }
            if (!"ah_did".equals(cookie.getName())) {
                continue;
            }
            String value = cookie.getValue();
            if (CryptoUtils.isValidUUID(value)) {
                return UUID.fromString(value);
            }
            return null;
        }
        return null;
    }
}
