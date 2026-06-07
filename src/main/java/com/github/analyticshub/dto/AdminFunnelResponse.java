package com.github.analyticshub.dto;

import java.util.List;

public record AdminFunnelResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        List<String> steps,
        String groupBy,
        String attributionModel,
        List<AdminFunnelGroupResult> groups
) {}
