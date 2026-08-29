package com.github.analyticshub.dto;

import java.util.Map;

/** 批量采集处理摘要；只返回有界计数和错误代码，不回显 payload。 */
public record EventBatchTrackSummary(
        int receivedCount,
        int acceptedCount,
        int insertedCount,
        int duplicateCount,
        int rejectedCount,
        Map<String, Integer> rejectionCounts
) {}
