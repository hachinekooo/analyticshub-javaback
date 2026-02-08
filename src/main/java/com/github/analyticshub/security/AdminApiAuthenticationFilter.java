package com.github.analyticshub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理端 API 认证过滤器
 */
@Component
public class AdminApiAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Value("${app.security.admin-token:}")
    private String adminToken;

    public AdminApiAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!isAdminApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (hasQueryToken(request)) {
            // Avoid leaking tokens via URL/query logs or caches.
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_TOKEN_INVALID",
                    "禁止在 URL 参数中传递管理端 Token，请使用请求头 X-Admin-Token 或 Authorization: Bearer <token>");
            return;
        }

        String token = extractAdminToken(request);
        if (token == null || token.isBlank()) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_TOKEN_MISSING",
                    "缺少管理端 Token，请使用请求头 X-Admin-Token 或 Authorization: Bearer <token>");
            return;
        }

        if (adminToken == null || adminToken.isBlank()) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ADMIN_TOKEN_NOT_CONFIGURED",
                    "管理端 Token 未配置，请设置 app.security.admin-token 或环境变量 ADMIN_TOKEN");
            return;
        }

        if (!constantTimeEquals(adminToken, token)) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_TOKEN_INVALID", "无效的管理端 Token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isAdminApiPath(String path) {
        return path != null && path.startsWith("/api/admin");
    }

    private static boolean hasQueryToken(HttpServletRequest request) {
        return request.getParameter("token") != null
                || request.getParameter("admin_token") != null
                || request.getParameter("access_token") != null;
    }

    private static String extractAdminToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String prefix = "Bearer ";
            if (authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return authorization.substring(prefix.length()).trim();
            }
        }
        // Fallback header used by the admin frontend.
        return request.getHeader("X-Admin-Token");
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            int max = Math.max(a.length, b.length);
            int result = 0;
            for (int i = 0; i < max; i++) {
                byte x = i < a.length ? a[i] : 0;
                byte y = i < b.length ? b[i] : 0;
                result |= x ^ y;
            }
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Object> error = ApiResponse.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
