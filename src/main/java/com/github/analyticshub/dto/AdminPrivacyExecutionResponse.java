package com.github.analyticshub.dto;

import java.util.Map;

/**
 * 客服在管理端手动执行隐私工单后的结果。
 *
 * @param summary 审计安全的处理摘要，会写入工单结果
 * @param exportData 仅导出请求返回的完整数据快照，不写入工单表
 */
public record AdminPrivacyExecutionResponse(
        String requestId,
        String requestType,
        String status,
        String executedAt,
        long version,
        String downloadFileName,
        Map<String, Object> summary,
        Map<String, Object> exportData
) {
}
