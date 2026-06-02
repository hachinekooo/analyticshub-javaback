package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record AdminPrivacyRequestUpdateRequest(
        @NotBlank(message = "status 不能为空")
        String status,

        @Size(max = 64, message = "operator 长度不能超过 64")
        String operator,

        @Size(max = 4000, message = "operatorNote 长度不能超过 4000")
        String operatorNote,

        Map<String, Object> resultPayload,

        Boolean notifyUser,

        @Size(max = 4000, message = "notificationMessage 长度不能超过 4000")
        String notificationMessage
) {
}
