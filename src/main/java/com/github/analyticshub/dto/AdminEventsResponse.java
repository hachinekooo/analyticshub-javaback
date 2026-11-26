package com.github.analyticshub.dto;

import java.util.List;

/**
 * 事件明细的分页结果，total 统计全部匹配记录，items 只包含当前页。
 */
public record AdminEventsResponse(
        String projectId,
        String rangeStart,
        /** 实际查询的结束时刻，不包含这个时刻发生的事件。 */
        String rangeEnd,
        int page,
        int pageSize,
        long total,
        List<AdminEventRecord> items
) {}
