package com.github.analyticshub.dto;

import java.util.Locale;

public enum PrivacyProcessor {
    ANALYTICSHUB,
    POSTHOG;

    public static PrivacyProcessor from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("processor 不能为空");
        }
        try {
            return PrivacyProcessor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("processor 仅支持 ANALYTICSHUB / POSTHOG");
        }
    }
}
