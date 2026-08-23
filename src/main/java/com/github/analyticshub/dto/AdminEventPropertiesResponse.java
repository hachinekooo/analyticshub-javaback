package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

/** 管理端按事件显式读取完整属性的响应。 */
public record AdminEventPropertiesResponse(
        String projectId,
        String eventId,
        JsonNode properties
) {}
