package com.github.analyticshub.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求日志过滤器
 * 记录 API 请求的关键信息（不记录敏感头/请求体）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(RequestLoggingFilter.class.getName());

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!shouldLog(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        long startNs = System.nanoTime();
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader("X-Request-Id", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = response.getStatus();
            String method = request.getMethod();
            String clientIp = resolveClientIp(request);
            String projectId = request.getHeader("X-Project-ID");

            String message = String.format(
                    "HTTP %s %s -> %d (%d ms) ip=%s projectId=%s requestId=%s",
                    method,
                    path,
                    status,
                    durationMs,
                    clientIp,
                    (projectId == null || projectId.isBlank()) ? "-" : projectId,
                    requestId
            );

            if (status >= 500) {
                log.log(System.Logger.Level.ERROR, message);
            } else if (status >= 400) {
                log.log(System.Logger.Level.WARNING, message);
            } else {
                log.log(System.Logger.Level.INFO, message);
            }
        }
    }

    private static boolean shouldLog(String path) {
        return path != null && (path.startsWith("/api") || path.startsWith("/actuator"));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
