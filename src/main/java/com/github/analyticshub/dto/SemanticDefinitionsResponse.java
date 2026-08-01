package com.github.analyticshub.dto;

import java.util.List;

public record SemanticDefinitionsResponse(
        String projectId,
        SemanticSourceKind sourceKind,
        List<SemanticDefinitionResponse> items
) {
}
