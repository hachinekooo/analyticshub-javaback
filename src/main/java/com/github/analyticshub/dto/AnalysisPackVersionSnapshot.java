package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** 可重新载入的 Pack 历史快照；恢复时仍需以更高版本重新提交。 */
public record AnalysisPackVersionSnapshot(
        int packVersion,
        Map<String, String> displayName,
        JsonNode manifest,
        String checksumSha256,
        String operation,
        Instant appliedAt
) {}
