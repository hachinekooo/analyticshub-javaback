package com.github.analyticshub.dto;

public record SemanticDeleteResponse(
        String projectId,
        SemanticSourceKind sourceKind,
        String semanticKey,
        String message
) {
}
