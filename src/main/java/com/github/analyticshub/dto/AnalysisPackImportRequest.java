package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public record AnalysisPackImportRequest(
        @NotNull Integer packVersion,
        @NotEmpty @Size(max = 16) Map<
                @Pattern(regexp = "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$") String,
                @Size(min = 1, max = 200) String> displayName,
        @NotNull JsonNode manifest,
        Boolean confirmDeactivations
) {}
