package com.github.analyticshub.dto;

import java.util.List;

public record AnalyticsPropertyDefinitionsResponse(
        String projectId,
        List<AnalyticsPropertyDefinitionResponse> items
) {}
