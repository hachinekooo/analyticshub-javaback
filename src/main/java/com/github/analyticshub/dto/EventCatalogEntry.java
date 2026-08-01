package com.github.analyticshub.dto;

import java.time.Instant;
import java.util.Map;

public record EventCatalogEntry(
        String rawKey,
        String semanticKey,
        boolean mapped,
        Map<String, String> displayName,
        String category,
        String description,
        long eventCount,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
