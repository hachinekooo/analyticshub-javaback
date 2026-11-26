package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

/**
 * 管理端 - 事件记录
 */
public record AdminEventRecord(
        String eventId,
        String eventType,
        /** 客户端记录的事件发生时间，单位为毫秒，列表按此排序。 */
        Long eventTimestamp,
        /** 服务端入库时间，排查延迟时可与发生时间对照。 */
        String createdAt,
        String deviceId,
        /** 事件写入时的原始统计身份；匿名时也可能有值，不代表已登录。 */
        String userId,
        /** 查询时按当前身份关联解析的统计身份；未关联时保留规范化后的原始身份。 */
        String resolvedActorId,
        /** 客户端声明的身份阶段，例如 anonymous / cloud_account / unknown。 */
        String identityScope,
        boolean actorLinked,
        String sessionId,
        JsonNode properties
) {}
