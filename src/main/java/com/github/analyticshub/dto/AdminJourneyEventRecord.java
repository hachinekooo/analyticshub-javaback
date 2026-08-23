package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

/**
 * 管理端用户旅程事件。
 *
 * <p>正常属性直接返回；极端大属性会延迟加载，避免一次旅程响应拖垮管理端。</p>
 */
public record AdminJourneyEventRecord(
        String eventId,
        String eventType,
        Long eventTimestamp,
        String createdAt,
        String deviceId,
        String userId,
        String resolvedActorId,
        String identityScope,
        boolean actorLinked,
        String sessionId,
        JsonNode properties,
        int propertiesBytes,
        boolean propertiesLoadable,
        boolean propertiesDeferred
) {}
