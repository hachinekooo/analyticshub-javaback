package com.github.analyticshub.dto;

/**
 * 管理端 - 设备记录
 */
public record AdminDeviceRecord(
        String deviceId,
        String apiKey,
        String deviceModel,
        String osVersion,
        String appVersion,
        Boolean isBanned,
        String banReason,
        String createdAt,
        String lastActiveAt
) {}
