package com.github.analyticshub.service;

import java.time.*;
import java.time.format.DateTimeParseException;

/**
 * 管理端查询通用工具
 */
public final class AdminQueryUtils {

    private AdminQueryUtils() {}

    public static Range resolveRange(String from, String to) {
        Instant end = parseInstant(to, true);
        if (end == null) {
            end = Instant.now();
        }
        Instant start = parseInstant(from, false);
        if (start == null) {
            start = end.minus(Duration.ofDays(7));
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        return new Range(start, end);
    }

    public static Paging resolvePaging(Integer page, Integer pageSize) {
        int size;
        if (pageSize != null && pageSize > 0) {
            size = pageSize;
        } else {
            size = 50;
        }
        size = Math.min(size, 200);

        int currentPage;
        int currentOffset;
        if (page != null && page > 0) {
            currentPage = page;
            currentOffset = (currentPage - 1) * size;
        } else {
            currentPage = 1;
            currentOffset = 0;
        }

        return new Paging(currentPage, size, currentOffset);
    }

    private static Instant parseInstant(String value, boolean endExclusive) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                LocalDate date = LocalDate.parse(trimmed);
                if (endExclusive) {
                    return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                }
                return date.atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("时间格式无效，请使用 ISO-8601 或 yyyy-MM-dd");
            }
        }
    }

    public record Range(Instant start, Instant end) {}
    public record Paging(int page, int pageSize, int offset) {}
}
