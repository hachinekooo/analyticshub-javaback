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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.service.TrafficMetricStatsService;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/traffic")
public class PublicTrafficController {

    private final TrafficMetricService trafficMetricService;
    private final TrafficMetricStatsService trafficMetricStatsService;
    private final String publicToken;
    private final Set<String> sameOriginHosts;

    public PublicTrafficController(TrafficMetricService trafficMetricService,
                                   TrafficMetricStatsService trafficMetricStatsService,
                                   @Value("${app.traffic.public-token:}") String publicToken,
                                   @Value("${app.traffic.same-origin-hosts:}") String sameOriginHosts) {
        this.trafficMetricService = trafficMetricService;
        this.trafficMetricStatsService = trafficMetricStatsService;
        this.publicToken = publicToken == null ? "" : publicToken;
        this.sameOriginHosts = parseConfiguredHosts(sameOriginHosts);
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

        String userAgent = httpServletRequest.getHeader("User-Agent");
        String referrer = resolveReferer(request, httpServletRequest);
        boolean bot = isBot(userAgent);

        TrafficMetricTrackRequest enrichedRequest = normalizePublicRequest(request, referrer, bot);

        return ApiResponse.success(
                // 官网公开流量只保留已声明的页面字段；IP 仅进入常规服务器日志，
                // 完整 User-Agent 与稳定 IP 哈希不写入原始流量事件。
                trafficMetricService.trackPublic(projectId, resolvedDeviceId, userId, enrichedRequest, null, null)
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

        String userAgent = httpServletRequest.getHeader("User-Agent");
        boolean bot = isBot(userAgent);

        if (items == null || items.length == 0) {
            throw new BusinessException("EMPTY_ITEMS", "请求体不能为空");
        }

        // 预处理批量数据中的 Referer 和 Bot 标记
        TrafficMetricTrackRequest[] processedItems = new TrafficMetricTrackRequest[items.length];
        for (int i = 0; i < items.length; i++) {
            TrafficMetricTrackRequest item = items[i];
            if (item == null) {
                processedItems[i] = null;
                continue;
            }
            String referrer = resolveReferer(item, httpServletRequest);
            processedItems[i] = normalizePublicRequest(item, referrer, bot);
        }

        int accepted = trafficMetricService.trackPublicBatch(
                projectId,
                resolvedDeviceId,
                userId,
                processedItems,
                null,
                null
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

    @DeleteMapping("/identity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearTrafficIdentity(HttpServletRequest request, HttpServletResponse response) {
        checkPublicToken(request);
        response.addCookie(createDeviceIdCookie("", 0, request));
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

    private static TrafficMetricTrackRequest normalizePublicRequest(
            TrafficMetricTrackRequest request,
            String referrer,
            boolean bot
    ) {
        ObjectNode metadata = bot ? JsonNodeFactory.instance.objectNode().put("isBot", true) : null;
        return new TrafficMetricTrackRequest(
                request.metricType(),
                sanitizePagePath(request.pagePath()),
                referrer,
                request.timestamp(),
                request.sessionId(),
                metadata
        );
    }

    private String resolveReferer(TrafficMetricTrackRequest request, HttpServletRequest httpServletRequest) {
        String referrer = request.referrer();
        if (referrer != null && !referrer.isBlank()) {
            return sanitizeReferrer(referrer);
        }
        String headerReferer = httpServletRequest.getHeader("Referer");
        return sanitizeReferrer(headerReferer);
    }

    private String sanitizeReferrer(String rawReferrer) {
        if (rawReferrer == null || rawReferrer.isBlank()) {
            return null;
        }
        String value = rawReferrer.trim();
        if (value.startsWith("//")) {
            try {
                URI uri = new URI(value);
                String host = normalizeHost(uri.getHost());
                if (host == null) return null;
                if (sameOriginHosts.contains(host)) {
                    String path = uri.getRawPath();
                    return path == null || path.isBlank() ? "/" : path;
                }
                return host;
            } catch (URISyntaxException ignored) {
                return null;
            }
        }
        if (value.startsWith("/")) {
            int boundary = firstBoundary(value);
            return value.substring(0, boundary);
        }

        try {
            URI uri = new URI(value);
            String referrerHost = normalizeHost(uri.getHost());
            if (referrerHost == null) {
                return sanitizeHostOnly(value);
            }
            if (sameOriginHosts.contains(referrerHost)) {
                String path = uri.getRawPath();
                return path == null || path.isBlank() ? "/" : path;
            }
            return referrerHost;
        } catch (URISyntaxException ignored) {
            return sanitizeHostOnly(value);
        }
    }

    private static String sanitizePagePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        String value = rawPath.trim();
        if (value.startsWith("/")) {
            return value.substring(0, firstBoundary(value));
        }
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static int firstBoundary(String value) {
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        if (queryIndex < 0) return fragmentIndex < 0 ? value.length() : fragmentIndex;
        if (fragmentIndex < 0) return queryIndex;
        return Math.min(queryIndex, fragmentIndex);
    }

    private static String sanitizeHostOnly(String value) {
        int boundary = firstBoundary(value);
        String candidate = value.substring(0, boundary);
        int slashIndex = candidate.indexOf('/');
        if (slashIndex >= 0) candidate = candidate.substring(0, slashIndex);
        return normalizeHost(candidate);
    }

    private static Set<String> parseConfiguredHosts(String rawHosts) {
        if (rawHosts == null || rawHosts.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(rawHosts.split(","))
                .map(PublicTrafficController::normalizeHost)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) return null;
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end >= 0 ? host.substring(0, end + 1) : null;
        }
        int portIndex = host.indexOf(':');
        if (portIndex >= 0) host = host.substring(0, portIndex);
        return host.matches("[a-z0-9.-]+") ? host : null;
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
        String headerDeviceId = request.getHeader("X-Device-ID");
        if (headerDeviceId != null) {
            try {
                UUID parsed = UUID.fromString(headerDeviceId);
                if (parsed.toString().equals(headerDeviceId)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // Converted to the stable public API error below.
            }
            throw new BusinessException(
                    "INVALID_DEVICE_ID",
                    "X-Device-ID 必须是 canonical UUID（小写、带连字符）",
                    HttpStatus.BAD_REQUEST
            );
        }

        // Same-origin callers can use the first-party cookie without SDK state.
        UUID cookieDeviceId = readDeviceIdCookie(request);
        if (cookieDeviceId != null) {
            return cookieDeviceId;
        }

        UUID assigned = UUID.randomUUID();
        response.addCookie(createDeviceIdCookie(assigned.toString(), 15552000, request));
        return assigned;
    }

    private static Cookie createDeviceIdCookie(String value, int maxAge, HttpServletRequest request) {
        Cookie cookie = new Cookie("ah_did", value);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(true);

        // 适配 Nginx 代理下的 HTTPS 识别。
        boolean secure = request.isSecure();
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && forwardedProto.equalsIgnoreCase("https")) {
            secure = true;
        }
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
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
