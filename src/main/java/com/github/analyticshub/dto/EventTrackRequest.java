package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record EventTrackRequest(
        @NotBlank(message = "事件类型不能为空") String eventType,
        @NotNull(message = "时间戳不能为空") Long timestamp,
        Map<String, Object> properties,
        UUID sessionId
) {}
