package com.github.analyticshub.dto;

import java.util.UUID;

public record ActorIdentityLinkResponse(
        UUID bindingId,
        UUID sourceActorId,
        UUID canonicalActorId,
        String status
) {
}
