package com.github.analyticshub.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SemanticDefinitionResponse(
        String projectId,
        SemanticSourceKind sourceKind,
        String semanticKey,
        Map<String, String> displayName,
        String category,
        String description,
        boolean isActive,
        List<String> aliases,
        Instant createdAt,
        Instant updatedAt
) {
}
