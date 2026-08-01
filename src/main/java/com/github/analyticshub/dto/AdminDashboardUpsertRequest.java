package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record AdminDashboardUpsertRequest(
        @NotNull(message = "displayName 不能为空")
        Map<String, String> displayName,

        @Size(max = 1000, message = "description 长度不能超过 1000")
        String description,

        @NotNull(message = "schemaVersion 不能为空")
        @Positive(message = "schemaVersion 必须大于 0")
        Integer schemaVersion,

        @NotNull(message = "definition 不能为空")
        Map<String, Object> definition,

        @PositiveOrZero(message = "expectedRevision 不能小于 0")
        Long expectedRevision,
        Boolean isDefault,
        Boolean isActive
) {
}
