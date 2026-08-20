package com.github.analyticshub.service;

import java.util.regex.Pattern;

/** Counter API 与 Dashboard 引用共同使用的稳定 Key 语法。 */
final class CounterKeyRules {

    private static final int MAX_LENGTH = 100;
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$"
    );

    private CounterKeyRules() {}

    static boolean isValid(String key) {
        return key != null
                && !key.isBlank()
                && key.length() <= MAX_LENGTH
                && key.equals(key.strip())
                && KEY_PATTERN.matcher(key).matches();
    }

    static String normalize(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("counter key 不能为空");
        }
        String normalized = key.strip();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("counter key 长度不能超过 100");
        }
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "counter key 仅支持字母、数字、点、下划线、冒号和连字符，且必须以字母或数字开头"
            );
        }
        return normalized;
    }
}
