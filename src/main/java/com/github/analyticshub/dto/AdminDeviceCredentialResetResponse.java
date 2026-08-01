package com.github.analyticshub.dto;

/**
 * One-time response returned after an administrator resets device credentials.
 * The secret key is intentionally not exposed by any read endpoint.
 */
public record AdminDeviceCredentialResetResponse(
        String projectId,
        String deviceId,
        String apiKey,
        String secretKey
) {}
