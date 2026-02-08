package com.github.analyticshub.dto;

/**
 * 管理端 - 会话记录
 */
public record AdminSessionRecord(
        String sessionId,
        String deviceId,
        String userId,
        String sessionStartTime,
        Long sessionDurationMs,
        String deviceModel,
        String osVersion,
        String appVersion,
        String buildNumber,
        Integer screenCount,
        Integer eventCount,
        String createdAt
) {}
