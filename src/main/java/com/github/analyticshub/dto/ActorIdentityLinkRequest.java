package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 受信业务后端提交的匿名 actor 与云账号 actor 绑定。
 *
 * <p>{@code bindingId} 是幂等键；重试必须携带完全相同的 source、canonical 和
 * linkedAt。使用同一个 bindingId 改写绑定时间会被视为冲突，避免历史事实被静默修改。</p>
 */
public record ActorIdentityLinkRequest(
        @NotNull UUID bindingId,
        @NotNull UUID sourceActorId,
        @NotNull UUID canonicalActorId,
        @NotNull Instant linkedAt
) {
}
