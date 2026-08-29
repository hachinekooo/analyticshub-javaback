package com.github.analyticshub.dto;

/** 标明指标结果可否作为稳定 KPI，避免诊断查询被误用为运营口径。 */
public enum AnalyticsMetricResultClassification {
    TRUSTED_SCHEMA,
    CROSS_VERSION_DIAGNOSTIC,
    UNGOVERNED_DIAGNOSTIC
}
