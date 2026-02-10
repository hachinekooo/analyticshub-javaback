package com.github.analyticshub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.service.EmailService;
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
 * 集成限流防护和邮件告警
 */
public class AdminApiAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final EmailService emailService;
    private final TwoFactorAuthService twoFactorAuthService;

    private final String adminToken;

    public AdminApiAuthenticationFilter(ObjectMapper objectMapper, 
                                       RateLimitService rateLimitService,
                                       EmailService emailService,
                                       TwoFactorAuthService twoFactorAuthService,
                                       String adminToken) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.emailService = emailService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.adminToken = adminToken;
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

        String clientIp = getClientIp(request);

        // 1. 检查 IP 是否被封禁
        if (rateLimitService.isBanned(clientIp)) {
            long remainingSeconds = rateLimitService.getRemainingBanTimeSeconds(clientIp);
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "TOO_MANY_ATTEMPTS",
                    String.format("由于多次失败尝试，您的 IP 已被临时封禁。请在 %d 秒后重试。", remainingSeconds));
            return;
        }

        if (hasQueryToken(request)) {
            rateLimitService.recordFailure(clientIp);
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
            // 记录失败
            rateLimitService.recordFailure(clientIp);
            int failureCount = rateLimitService.getFailureCount(clientIp);

            // 达到封禁阈值时发送告警邮件
            if (failureCount == 5) {
                emailService.sendBruteForceAlert(clientIp, failureCount);
            }

            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_TOKEN_INVALID", 
                    String.format("无效的管理端 Token（失败 %d 次）", failureCount));
            return;
        }

        // 认证成功，重置失败记录
        rateLimitService.resetFailures(clientIp);

        // 2FA 双因素认证检查
        if (twoFactorAuthService.isEnabled()) {
            // 1. 检查是否在信任列表
            if (!twoFactorAuthService.isTrusted(clientIp)) {
                
                // 2. 尝试获取并验证 OTP
                String otpCode = request.getHeader("X-Admin-OTP");
                if (otpCode != null && !otpCode.isBlank()) {
                    try {
                        int code = Integer.parseInt(otpCode.trim());
                        if (twoFactorAuthService.verifyCode(code)) {
                            // 验证成功，加入信任列表
                            twoFactorAuthService.trustDevice(clientIp);
                            filterChain.doFilter(request, response);
                            return;
                        } else {
                            // OTP 错误
                            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "INVALID_OTP", 
                                    "动态验证码错误，请检查 Authenticator App");
                            return;
                        }
                    } catch (NumberFormatException e) {
                        sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_OTP_FORMAT", 
                                "动态验证码格式错误");
                        return;
                    }
                }

                // 3. 未提供 OTP 且不在信任列表 -> 拦截
                sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "REQUIRE_2FA", 
                        "检测到异常/新环境登录，需要双因素认证。请提供 6 位动态验证码 (Header: X-Admin-OTP)。");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 取第一个 IP（如果有多个代理）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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
