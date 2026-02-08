package com.github.analyticshub.dto;

import java.util.List;

/**
 * 管理端 - 会话列表响应
 */
public record AdminSessionsResponse(
        String projectId,
        String rangeStart,
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminSessionRecord> items
) {}
