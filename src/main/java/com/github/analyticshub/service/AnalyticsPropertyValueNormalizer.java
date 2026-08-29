package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;

import java.math.BigDecimal;

/** 统一属性定义、筛选和质量检查使用的标量文本规范形式。 */
final class AnalyticsPropertyValueNormalizer {

    static final int MAX_VALUE_LENGTH = 200;

    private AnalyticsPropertyValueNormalizer() {}

    static String normalize(String value, AnalyticsPropertyDataType type) {
        if (value == null || type == null || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("属性值为空或长度超限");
        }
        String stripped = trimBoundaryWhitespace(value);
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("属性值不能为空");
        }
        return switch (type) {
            case STRING -> stripped;
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(stripped) && !"false".equalsIgnoreCase(stripped)) {
                    throw new IllegalArgumentException("BOOLEAN 属性只接受 true 或 false");
                }
                yield stripped.toLowerCase(java.util.Locale.ROOT);
            }
            case INTEGER -> {
                try {
                    yield Long.toString(Long.parseLong(stripped));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("INTEGER 属性值格式无效", exception);
                }
            }
            case NUMBER -> {
                try {
                    yield new BigDecimal(stripped).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("NUMBER 属性值格式无效", exception);
                }
            }
        };
    }

    /** 与 PostgreSQL 查询侧保持一致，只规范化协议明确支持的边界空白字符。 */
    private static String trimBoundaryWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBoundaryWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isBoundaryWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isBoundaryWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
    }
}
