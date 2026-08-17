package com.github.analyticshub.controller;

import com.github.analyticshub.dto.ActorIdentityLinkRequest;
import com.github.analyticshub.dto.ActorIdentityLinkResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.ActorIdentityLinkService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalActorIdentityControllerTest {

    @Test
    void signedIdempotencyKeyMustMatchThePersistedBindingId() {
        ActorIdentityLinkService service = mock(ActorIdentityLinkService.class);
        InternalActorIdentityController controller = new InternalActorIdentityController(service);
        ActorIdentityLinkRequest request = request();

        assertThatThrownBy(() -> controller.link("project-test", UUID.randomUUID(), request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("ACTOR_LINK_IDEMPOTENCY_MISMATCH");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(400);
                });
        verifyNoInteractions(service);
    }

    @Test
    void matchingIdempotencyKeyDelegatesTheAuthenticatedProjectAndPayload() {
        ActorIdentityLinkService service = mock(ActorIdentityLinkService.class);
        InternalActorIdentityController controller = new InternalActorIdentityController(service);
        ActorIdentityLinkRequest request = request();
        ActorIdentityLinkResponse expected = new ActorIdentityLinkResponse(
                request.bindingId(),
                request.sourceActorId(),
                request.canonicalActorId(),
                "created"
        );
        when(service.link("project-test", request)).thenReturn(expected);

        assertThat(controller.link("project-test", request.bindingId(), request).data())
                .isEqualTo(expected);
        verify(service).link("project-test", request);
    }

    private static ActorIdentityLinkRequest request() {
        return new ActorIdentityLinkRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-08-17T00:00:00Z")
        );
    }
}
