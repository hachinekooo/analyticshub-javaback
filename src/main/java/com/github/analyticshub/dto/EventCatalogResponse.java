package com.github.analyticshub.dto;

import java.util.List;

public record EventCatalogResponse(
        String projectId,
        SemanticSourceKind sourceKind,
        List<EventCatalogEntry> items
) {
}
