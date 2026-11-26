package com.github.analyticshub.dto;

/**
 * 管理员重置设备凭据时一次性返回的新凭据；任何只读接口都不返回密钥。
 */
public record AdminDeviceCredentialResetResponse(
        String projectId,
        String deviceId,
        String apiKey,
        String secretKey
) {}
