package com.github.analyticshub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理端交互式产品分析的资源预算。
 *
 * <p>预算用于拒绝无法在交互窗口内完整计算的查询，不能用于截断后返回部分结果。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.analytics.query")
public class AnalyticsQueryProperties {

    private int maxRangeDays = 180;
    private int maxCandidateRows = 200_000;
    private int maxDataQualityRows = 50_000;
    private int maxFunnelGroups = 1_000;
    private int maxDimensionValueLength = 256;
    private int timeoutSeconds = 15;

    public int getMaxRangeDays() {
        return maxRangeDays;
    }

    public void setMaxRangeDays(int maxRangeDays) {
        this.maxRangeDays = positive(maxRangeDays, "max-range-days");
    }

    public int getMaxCandidateRows() {
        return maxCandidateRows;
    }

    public void setMaxCandidateRows(int maxCandidateRows) {
        this.maxCandidateRows = positive(maxCandidateRows, "max-candidate-rows");
    }

    public int getMaxDataQualityRows() {
        return maxDataQualityRows;
    }

    public void setMaxDataQualityRows(int maxDataQualityRows) {
        this.maxDataQualityRows = positive(maxDataQualityRows, "max-data-quality-rows");
    }

    public int getMaxFunnelGroups() {
        return maxFunnelGroups;
    }

    public void setMaxFunnelGroups(int maxFunnelGroups) {
        this.maxFunnelGroups = positive(maxFunnelGroups, "max-funnel-groups");
    }

    public int getMaxDimensionValueLength() {
        return maxDimensionValueLength;
    }

    public void setMaxDimensionValueLength(int maxDimensionValueLength) {
        this.maxDimensionValueLength = positive(maxDimensionValueLength, "max-dimension-value-length");
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = positive(timeoutSeconds, "timeout-seconds");
    }

    private static int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException("app.analytics.query." + field + " 必须大于 0");
        }
        return value;
    }
}
