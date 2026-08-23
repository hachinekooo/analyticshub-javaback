package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 以真实事件为锚点的用户旅程片段。
 */
public record AdminEventJourneyResponse(
        String projectId,
        String anchorEventId,
        String subjectType,
        String resolvedActorId,
        String rangeStart,
        String rangeEnd,
        long total,
        boolean truncated,
        List<AdminJourneyEventRecord> items
) {}
