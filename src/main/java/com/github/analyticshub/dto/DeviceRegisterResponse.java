package com.github.analyticshub.dto;

/**
 * 设备注册响应 DTO
 */
public record DeviceRegisterResponse(String apiKey, String secretKey, boolean isNew) {}
