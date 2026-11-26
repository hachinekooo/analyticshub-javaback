package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminDeviceCredentialResetResponse;
import com.github.analyticshub.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员为无法使用原凭据的设备重置采集凭据；新密钥只在重置响应返回。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/projects/{projectId}/devices")
public class AdminDeviceCredentialController {

    private final AuthService authService;

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
