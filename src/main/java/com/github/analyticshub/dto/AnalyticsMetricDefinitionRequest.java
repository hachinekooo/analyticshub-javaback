package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public record AnalyticsMetricDefinitionRequest(
        @NotEmpty @Size(max = 16) Map<
                @NotBlank @Pattern(regexp = "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$") String,
                @NotBlank @Size(max = 200) String> displayName,
        @NotNull AnalyticsMetricType metricType,
        @NotNull JsonNode definition,
        @Size(max = 1000) String description,
        @NotNull Boolean active
) {}
