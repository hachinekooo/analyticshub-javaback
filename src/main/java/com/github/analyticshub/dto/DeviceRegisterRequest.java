package com.github.analyticshub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 设备注册请求 DTO (record)
 */
public record DeviceRegisterRequest(
    @NotBlank(message = "设备ID不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        message = "设备ID必须是有效的UUID格式")
    String deviceId,
    String deviceModel,
    String osVersion,
    String appVersion
) {}
