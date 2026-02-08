package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record SessionUploadRequest(
        @NotNull(message = "会话ID不能为空") UUID sessionId,
        @NotNull(message = "会话开始时间不能为空") Instant sessionStartTime,
        Long sessionDurationMs,
        String deviceModel,
        String osVersion,
        String appVersion,
        String buildNumber,
        Integer screenCount,
        Integer eventCount
) {}
