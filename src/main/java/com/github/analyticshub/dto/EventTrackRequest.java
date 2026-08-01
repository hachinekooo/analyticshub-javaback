package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record EventTrackRequest(
        @NotBlank(message = "事件类型不能为空")
        @Size(max = 100, message = "事件类型不能超过100个字符") String eventType,
        @NotNull(message = "时间戳不能为空") Long timestamp,
        Map<String, Object> properties,
        UUID sessionId,
        @Size(max = 256, message = "幂等键不能超过256个字符") String idempotencyKey
) {}
