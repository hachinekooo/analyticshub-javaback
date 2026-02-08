package com.github.analyticshub.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 管理端 - 事件记录
 */
public record AdminEventRecord(
        String eventId,
        String eventType,
        Long eventTimestamp,
        String createdAt,
        String deviceId,
        String userId,
        String sessionId,
        JsonNode properties
) {}
