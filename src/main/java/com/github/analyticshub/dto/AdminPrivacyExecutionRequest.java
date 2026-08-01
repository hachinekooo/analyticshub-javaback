package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 管理员手动执行隐私工单时提交的并发版本与操作确认。
 */
public record AdminPrivacyExecutionRequest(
        @NotNull(message = "version 不能为空")
        @PositiveOrZero(message = "version 不能小于 0")
        Long version,

        @NotBlank(message = "operator 不能为空")
        @Size(max = 64, message = "operator 长度不能超过 64")
        String operator,

        @Size(max = 64, message = "confirmation 长度不能超过 64")
        String confirmation
) {
}
