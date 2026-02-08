package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import com.github.analyticshub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 认证控制器
 * 处理设备注册等认证相关请求
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final System.Logger log = System.getLogger(AuthController.class.getName());

    private final AuthService authService;

    @Value("${app.security.admin-token:}")
    private String adminToken;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 设备注册接口
     * POST /api/v1/auth/register
     */
    @PostMapping("/register")
    public ApiResponse<DeviceRegisterResponse> register(
            @RequestHeader(value = "X-Project-ID") String projectId,
            @Valid @RequestBody DeviceRegisterRequest request) {
        
        log.log(System.Logger.Level.INFO, "设备注册请求: projectId={0}, deviceId={1}", projectId, request.deviceId());
        
        DeviceRegisterResponse response = authService.registerDevice(projectId, request);
        return ApiResponse.success(response);
    }

    @PostMapping("/admin-token/verify")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> verifyAdminToken(
            HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Admin-Token", required = false) String xAdminToken) {

        // Admin token must come from headers to avoid leaking through URLs/logs.
        if (hasQueryToken(request)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("ADMIN_TOKEN_INVALID", "请使用请求头传递管理端 Token"));
        }

        String token = extractAdminToken(authorization, xAdminToken);
        if (token == null || token.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("ADMIN_TOKEN_MISSING", "缺少管理端 Token"));
        }

        if (adminToken == null || adminToken.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("ADMIN_TOKEN_NOT_CONFIGURED", "管理端 Token 未配置"));
        }

        if (!constantTimeEquals(adminToken, token)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("ADMIN_TOKEN_INVALID", "无效的管理端 Token"));
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of("valid", true)));
    }

    private static boolean hasQueryToken(HttpServletRequest request) {
        return request.getParameter("token") != null
                || request.getParameter("admin_token") != null
                || request.getParameter("access_token") != null;
    }

    private static String extractAdminToken(String authorization, String xAdminToken) {
        if (authorization != null) {
            String prefix = "Bearer ";
            if (authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return authorization.substring(prefix.length()).trim();
            }
        }
        return xAdminToken;
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
}
