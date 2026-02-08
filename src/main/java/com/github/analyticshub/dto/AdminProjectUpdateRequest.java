package com.github.analyticshub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端更新项目请求 DTO
 */
public record AdminProjectUpdateRequest(
        @Size(max = 100, message = "projectName 长度不能超过 100")
        String projectName,
        String dbHost,
        @Min(value = 1, message = "dbPort 不能小于 1")
        @Max(value = 65535, message = "dbPort 不能大于 65535")
        Integer dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        @Size(max = 40, message = "tablePrefix 长度不能超过 40")
        @Pattern(regexp = "^[a-z0-9_]*$", message = "tablePrefix 格式无效")
        String tablePrefix,
        Boolean isActive
) {}
