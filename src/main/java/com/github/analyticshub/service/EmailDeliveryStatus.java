package com.github.analyticshub.service;

/**
 * 一次同步邮件投递的结果，区分投递失败、邮件停用和收件地址不合规。
 */
public enum EmailDeliveryStatus {
    SENT,
    FAILED,
    DISABLED,
    INVALID_RECIPIENT
}
