package com.github.analyticshub.dto;

/** 受限属性筛选只允许可预测、可索引的操作。 */
public enum AnalyticsPropertyFilterOperator {
    EQ,
    IN,
    EXISTS
}
