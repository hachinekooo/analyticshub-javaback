package com.github.analyticshub.dto;

/**
 * 管理端 - 设备记录
 */
public record AdminDeviceRecord(
        String deviceId,
        String apiKey,
        String deviceModel,
        String osVersion,
        /** 当前设备记录的注册版本快照；当前使用版本应查询活跃版本指标。 */
        String appVersion,
        Boolean isBanned,
        String banReason,
        String createdAt,
        String lastActiveAt
) {}
