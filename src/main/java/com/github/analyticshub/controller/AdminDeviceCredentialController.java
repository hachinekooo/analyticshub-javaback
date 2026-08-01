package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminDeviceCredentialResetResponse;
import com.github.analyticshub.service.AuthService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only recovery endpoint for a device that no longer owns valid credentials.
 */
@RestController
@RequestMapping("/api/admin/projects/{projectId}/devices")
public class AdminDeviceCredentialController {

    private final AuthService authService;

    public AdminDeviceCredentialController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/{deviceId}/credentials/reset")
    public ResponseEntity<ApiResponse<AdminDeviceCredentialResetResponse>> resetCredentials(
            @PathVariable String projectId,
            @PathVariable String deviceId
    ) {
        AdminDeviceCredentialResetResponse reset = authService.resetCredentialsByAdmin(projectId, deviceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(reset));
    }
}
