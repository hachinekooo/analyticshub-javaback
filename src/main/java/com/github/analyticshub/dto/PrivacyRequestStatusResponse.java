package com.github.analyticshub.dto;

/**
 * 面向请求发起设备的最小工单状态。
 *
 * <p>运营人员、内部备注、处理载荷和原始 metadata 只属于管理端契约，
 * 不得从采集端接口返回。</p>
 */
public record PrivacyRequestStatusResponse(
        String requestId,
        String requestType,
        String processor,
        String status,
        String contactEmail,
        String requestedAt,
        String processedAt,
        String closedAt,
        String updatedAt
) {
}
