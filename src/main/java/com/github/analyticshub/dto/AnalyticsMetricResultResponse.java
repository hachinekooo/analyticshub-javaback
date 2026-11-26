package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;

/**
 * 一次指标查询的结果，已按该指标规则计算，不按采集协议版本拆包。
 * @param result 指标对应的人数、次数、趋势或分组等结果
 * @param resultClassification 当前结果按可信规则计算，还是仅可用于诊断
 * @param diagnosticReason 仅跨版本诊断结果返回指标配置中的原因，其他情况下为空
 */
public record AnalyticsMetricResultResponse(
        String projectId,
        String metricKey,
        AnalyticsMetricType metricType,
        String from,
        String to,
        AnalyticsMetricResultClassification resultClassification,
        String diagnosticReason,
        JsonNode result
) {}
