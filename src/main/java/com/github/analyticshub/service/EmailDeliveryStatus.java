package com.github.analyticshub.service;

/**
 * Explicit result of one synchronous email delivery attempt.
 */
public enum EmailDeliveryStatus {
    SENT,
    FAILED,
    DISABLED,
    INVALID_RECIPIENT
}
