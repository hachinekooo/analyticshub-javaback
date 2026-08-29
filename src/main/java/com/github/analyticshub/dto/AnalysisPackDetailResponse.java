package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/** 可恢复的 Analysis Pack 服务端事实；Manifest 仍由声明式校验边界约束。 */
public record AnalysisPackDetailResponse(
        String projectId,
        String packKey,
        int packVersion,
        Map<String, String> displayName,
        JsonNode manifest,
        String checksumSha256,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        List<AnalysisPackVersionSnapshot> versions
) {}
