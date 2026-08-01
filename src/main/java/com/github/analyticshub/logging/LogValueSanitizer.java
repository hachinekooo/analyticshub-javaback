package com.github.analyticshub.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Central allow-list for values written to single-line application logs.
 * Invalid client-controlled values are replaced instead of truncated so a
 * malicious prefix cannot be mistaken for a trusted identifier.
 */
public final class LogValueSanitizer {

    public static final String ABSENT_VALUE = "-";
    public static final String INVALID_VALUE = "<invalid>";
    public static final String INVALID_PATH = "<invalid-path>";

    private static final Pattern SAFE_PATH = Pattern.compile(
            "^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,511}$"
    );
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    );
    private static final Pattern SAFE_PROJECT_ID = Pattern.compile(
            "^[a-z0-9_-]{1,50}$"
    );
    private static final Pattern SAFE_EVENT_TYPE = Pattern.compile(
            "^[A-Za-z0-9._:-]{1,100}$"
    );
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile(
            "^[A-Z0-9_]{1,64}$"
    );

    private LogValueSanitizer() {
    }

    public static String path(String value) {
        return value != null && SAFE_PATH.matcher(value).matches()
                ? value
                : INVALID_PATH;
    }

    public static String requestIdOrRandom(String value) {
        if (value != null && SAFE_REQUEST_ID.matcher(value).matches()) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    public static String projectId(String value) {
        return sanitizedToken(value, SAFE_PROJECT_ID);
    }

    public static String eventType(String value) {
        return sanitizedToken(value, SAFE_EVENT_TYPE);
    }

    public static String errorCode(String value) {
        return sanitizedToken(value, SAFE_ERROR_CODE);
    }

    private static String sanitizedToken(String value, Pattern allowList) {
        if (value == null || value.isBlank()) {
            return ABSENT_VALUE;
        }
        return allowList.matcher(value).matches() ? value : INVALID_VALUE;
    }
}
