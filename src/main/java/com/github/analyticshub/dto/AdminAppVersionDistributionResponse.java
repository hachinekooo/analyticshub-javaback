package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端活跃设备版本分布。
 */
public record AdminAppVersionDistributionResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        String measurement,
        long activeDevices,
        long versionKnownDevices,
        double coverageRate,
        List<AdminAppVersionDistributionItem> items
) {}
