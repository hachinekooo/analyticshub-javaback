package com.github.analyticshub.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.github.analyticshub.security.ClientIpResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
/**
 * 请求日志过滤器
 * 记录 API 请求的关键信息（不记录敏感头/请求体）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(RequestLoggingFilter.class.getName());
    private final ClientIpResolver clientIpResolver;

    public RequestLoggingFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

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
        String requestId = LogValueSanitizer.requestIdOrRandom(
                request.getHeader("X-Request-Id")
        );
        response.setHeader("X-Request-Id", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = response.getStatus();
            String method = request.getMethod();
            String clientIp = maskIp(clientIpResolver.resolve(request));
            String logPath = LogValueSanitizer.path(path);
            String projectId = LogValueSanitizer.projectId(
                    request.getHeader("X-Project-ID")
            );

            String message = String.format(
                    "HTTP %s %s -> %d (%d ms) ip=%s projectId=%s requestId=%s",
                    method,
                    logPath,
                    status,
                    durationMs,
                    clientIp,
                    projectId,
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

    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "-";
        }
        if (ip.contains(":")) {
            String[] visibleParts = ip.split(":");
            String first = null;
            String second = null;
            for (String part : visibleParts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                if (first == null) {
                    first = part;
                    continue;
                }
                second = part;
                break;
            }
            if (first == null || second == null) {
                return "***";
            }
            return first + ":" + second + ":***";
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return "***";
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".***";
    }
}
