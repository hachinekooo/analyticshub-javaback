package com.github.analyticshub.dto;

import java.util.Locale;

public enum PrivacyRequestStatus {
    SUBMITTED,
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    CANCELLED;

    public static PrivacyRequestStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        try {
            return PrivacyRequestStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status 仅支持 SUBMITTED / IN_PROGRESS / COMPLETED / REJECTED / CANCELLED");
        }
    }

    public boolean isFinalStatus() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED;
    }
}
