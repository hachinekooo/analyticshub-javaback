package com.github.analyticshub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record PrivacyRequestSubmitRequest(
        @NotBlank(message = "contactEmail 不能为空")
        @Email(message = "contactEmail 格式无效")
        @Size(max = 255, message = "contactEmail 长度不能超过 255")
        String contactEmail,

        @NotBlank(message = "processor 不能为空")
        @Size(max = 32, message = "processor 长度不能超过 32")
        String processor,

        @Size(max = 32, message = "source 长度不能超过 32")
        String source,

        @Size(max = 4000, message = "requesterNote 长度不能超过 4000")
        String requesterNote,

        Map<String, Object> metadata
) {
}
