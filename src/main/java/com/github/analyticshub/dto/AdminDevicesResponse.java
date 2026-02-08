package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 设备列表响应
 */
public record AdminDevicesResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminDeviceRecord> items
) {}
