package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TrafficMetricTrackRequest(
        @NotBlank(message = "metricType 不能为空")
        @Size(max = 50, message = "metricType 长度不能超过 50")
        String metricType,

        @Size(max = 255, message = "pagePath 长度不能超过 255")
        String pagePath,

        @Size(max = 255, message = "referrer 长度不能超过 255")
        String referrer,

        Long timestamp,
        UUID sessionId,
        JsonNode metadata
) {}
