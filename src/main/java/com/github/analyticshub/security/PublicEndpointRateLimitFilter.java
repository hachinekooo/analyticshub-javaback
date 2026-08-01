package com.github.analyticshub.security;

import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-process fixed-window protection for anonymous endpoints that can create
 * data or verify credentials. It is intentionally a safety baseline; clustered
 * deployments should also enforce a shared limit at the reverse proxy/gateway.
 */
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final long MIN_CLEANUP_INTERVAL_MS = 10_000;

    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;
    private final boolean enabled;
    private final int maxRequests;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong();

    public PublicEndpointRateLimitFilter(
            ObjectMapper objectMapper,
            ClientIpResolver clientIpResolver,
            boolean enabled,
            int maxRequests,
            long windowMs
    ) {
        this(objectMapper, clientIpResolver, enabled, maxRequests, windowMs, Clock.systemUTC());
    }

    PublicEndpointRateLimitFilter(
            ObjectMapper objectMapper,
            ClientIpResolver clientIpResolver,
            boolean enabled,
            int maxRequests,
            long windowMs,
            Clock clock
    ) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("app.rate-limit.requests 必须大于 0");
        }
        if (windowMs < 1_000) {
            throw new IllegalArgumentException("app.rate-limit.window-ms 必须至少为 1000");
        }
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        RequestPathSecurityPolicy.Inspection requestPath = RequestPathSecurityPolicy.inspect(request);
        if (requestPath.unsafe()) {
            return false;
        }
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = requestPath.applicationPath();
        if (path == null) {
            return true;
        }
        return !(isPathOrDescendant(path, "/api/public")
                || ("POST".equalsIgnoreCase(request.getMethod())
                && (path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/admin-token/verify"))));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RequestPathSecurityPolicy.Inspection requestPath = RequestPathSecurityPolicy.inspect(request);
        if (RequestPathSecurityPolicy.rejectIfUnsafe(requestPath, response, objectMapper)) {
            return;
        }

        long now = clock.millis();
        cleanupIfNeeded(now);
        String clientIp = clientIpResolver.resolve(request);
        Window window = windows.compute(clientIp, (key, existing) -> {
            if (existing == null || now - existing.startedAtMs() >= windowMs) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAtMs(), existing.count() + 1);
        });

        if (window.count() <= maxRequests) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, (windowMs - (now - window.startedAtMs()) + 999) / 1_000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试")
        ));
    }

    private void cleanupIfNeeded(long now) {
        long previous = lastCleanup.get();
        long cleanupInterval = Math.max(MIN_CLEANUP_INTERVAL_MS, windowMs);
        if (now - previous < cleanupInterval || !lastCleanup.compareAndSet(previous, now)) {
            return;
        }
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMs() >= windowMs);
    }

    private static boolean isPathOrDescendant(String path, String basePath) {
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private record Window(long startedAtMs, int count) {}
}
