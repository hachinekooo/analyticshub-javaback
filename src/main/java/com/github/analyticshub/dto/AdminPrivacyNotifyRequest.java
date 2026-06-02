package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPrivacyNotifyRequest(
        @NotBlank(message = "subject 不能为空")
        @Size(max = 120, message = "subject 长度不能超过 120")
        String subject,

        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000")
        String message,

        @Size(max = 64, message = "operator 长度不能超过 64")
        String operator
) {
}
