package com.github.analyticshub.dto;

public record AdminFunnelStepResult(
        int stepIndex,
        String eventType,
        /** 去重的统计身份数；按操作流程统计时，此值是去重的流程数。 */
        long users,
        double conversionRate,
        /** 比上一步少的比例；里程碑各自与首步比较，不返回逐步流失率。 */
        Double dropOffRate
) {}
