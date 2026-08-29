package com.github.analyticshub.dto;

import java.util.List;

public record AnalyticsPropertyFilter(
        String propertyKey,
        AnalyticsPropertyFilterOperator operator,
        List<String> values
) {}
