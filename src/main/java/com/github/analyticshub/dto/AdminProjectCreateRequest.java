package com.github.analyticshub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端创建项目请求 DTO
 */
public record AdminProjectCreateRequest(
        @NotBlank(message = "projectId 不能为空")
        @Size(max = 50, message = "projectId 长度不能超过 50")
        @Pattern(regexp = "^[a-z0-9_-]+$", message = "projectId 格式无效")
        String projectId,
        @NotBlank(message = "projectName 不能为空")
        @Size(max = 100, message = "projectName 长度不能超过 100")
        String projectName,
        @NotBlank(message = "dbHost 不能为空")
        String dbHost,
        @Min(value = 1, message = "dbPort 不能小于 1")
        @Max(value = 65535, message = "dbPort 不能大于 65535")
        Integer dbPort,
        @NotBlank(message = "dbName 不能为空")
        String dbName,
        @NotBlank(message = "dbUser 不能为空")
        String dbUser,
        String dbPassword,
        @Size(max = 40, message = "tablePrefix 长度不能超过 40")
        @Pattern(regexp = "^[a-z0-9_]*$", message = "tablePrefix 格式无效")
        String tablePrefix
) {}
