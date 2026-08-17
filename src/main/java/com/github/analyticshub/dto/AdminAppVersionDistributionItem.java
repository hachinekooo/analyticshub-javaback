package com.github.analyticshub.dto;

/**
 * 所选时间范围内，活跃设备最后一次事件对应的 App 版本分组。
 */
public record AdminAppVersionDistributionItem(
        String appVersion,
        String buildNumber,
        long activeDevices,
        double share,
        String lastObservedAt
) {}
