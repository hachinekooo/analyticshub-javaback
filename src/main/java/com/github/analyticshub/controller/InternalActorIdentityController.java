package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.dto.ActorIdentityLinkRequest;
import com.github.analyticshub.dto.ActorIdentityLinkResponse;
import com.github.analyticshub.service.ActorIdentityLinkService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** 仅供受信业务后端调用的 actor 身份绑定入口。 */
@RestController
@RequestMapping("/internal/v1/analytics/actor-links")
public class InternalActorIdentityController {

    private final ActorIdentityLinkService actorIdentityLinkService;

    public InternalActorIdentityController(ActorIdentityLinkService actorIdentityLinkService) {
        this.actorIdentityLinkService = actorIdentityLinkService;
    }

    @PostMapping
    public ApiResponse<ActorIdentityLinkResponse> link(
            @RequestHeader("X-Project-ID") String projectId,
            @RequestHeader("X-Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ActorIdentityLinkRequest request
    ) {
        if (!idempotencyKey.equals(request.bindingId())) {
            throw new BusinessException(
                    "ACTOR_LINK_IDEMPOTENCY_MISMATCH",
                    "幂等键与绑定请求不一致",
                    HttpStatus.BAD_REQUEST
            );
        }
        return ApiResponse.success(actorIdentityLinkService.link(projectId, request));
    }
}
