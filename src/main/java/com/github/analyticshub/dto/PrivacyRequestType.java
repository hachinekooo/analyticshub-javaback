package com.github.analyticshub.dto;

import java.util.Locale;

public enum PrivacyRequestType {
    EXPORT,
    DELETE;

    public static PrivacyRequestType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("requestType 不能为空");
        }
        try {
            return PrivacyRequestType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("requestType 仅支持 EXPORT / DELETE");
        }
    }
}
