package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

/**
 * 管理端 - 事件记录
 */
public record AdminEventRecord(
        String eventId,
        String eventType,
        Long eventTimestamp,
        String createdAt,
        String deviceId,
        /** 事件写入时的原始统计 actor；历史字段名保留为 userId 以兼容现有客户端。 */
        String userId,
        /** 按当前 alias 规则解析后的归一 actor；未发生关联时与 userId 相同。 */
        String resolvedActorId,
        /** 客户端声明的身份阶段，例如 anonymous / cloud_account / unknown。 */
        String identityScope,
        boolean actorLinked,
        String sessionId,
        JsonNode properties
) {}
