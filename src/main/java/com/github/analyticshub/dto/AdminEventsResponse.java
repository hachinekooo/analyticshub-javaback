package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 事件记录列表响应
 */
public record AdminEventsResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminEventRecord> items
) {}
