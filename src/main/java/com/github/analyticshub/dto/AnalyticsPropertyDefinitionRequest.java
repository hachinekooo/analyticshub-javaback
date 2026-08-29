package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record AnalyticsPropertyDefinitionRequest(
        @NotEmpty @Size(max = 16) Map<
                @NotBlank @Pattern(regexp = "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$") String,
                @NotBlank @Size(max = 200) String> displayName,
        @NotNull AnalyticsPropertyDataType dataType,
        @Size(max = 1000) String description,
        @Size(max = 100) List<@NotBlank @Size(max = 200) String> allowedValues,
        @NotNull Boolean filterable,
        @NotNull Boolean groupable,
        @NotNull Boolean journeyKey,
        @NotNull Boolean sensitive,
        @NotNull Boolean active
) {}
