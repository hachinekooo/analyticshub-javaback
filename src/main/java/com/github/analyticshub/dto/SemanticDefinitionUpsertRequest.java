package com.github.analyticshub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record SemanticDefinitionUpsertRequest(
        @NotNull(message = "sourceKind 不能为空")
        SemanticSourceKind sourceKind,
        @NotEmpty(message = "displayName 不能为空")
        @Size(max = 16, message = "displayName 最多支持16个语言项")
        Map<
                @NotBlank(message = "displayName 语言 key 不能为空")
                @Pattern(
                        regexp = "^(?:default|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*)$",
                        message = "displayName 语言 key 格式无效"
                ) String,
                @NotBlank(message = "displayName 展示名称不能为空")
                @Size(max = 200, message = "displayName 展示名称不能超过200个字符") String
                > displayName,
        @Size(max = 100, message = "category 不能超过100个字符")
        String category,
        @Size(max = 1000, message = "description 不能超过1000个字符")
        String description,
        @NotNull(message = "isActive 不能为空")
        Boolean isActive,
        @NotNull(message = "aliasMode 不能为空")
        SemanticAliasUpdateMode aliasMode,
        @Valid
        @Size(max = 500, message = "aliases 最多支持500项")
        List<
                @NotBlank(message = "alias key 不能为空")
                @Size(max = 100, message = "alias key 不能超过100个字符") String
                > aliases
) {
}
