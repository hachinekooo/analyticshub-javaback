package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminTrafficMetricsResponse;
import com.github.analyticshub.service.AdminTrafficMetricQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端流量指标查询接口
 */
@RestController
@RequestMapping("/api/admin/traffic-metrics")
public class AdminTrafficMetricController {

    private final AdminTrafficMetricQueryService adminTrafficMetricQueryService;

    public AdminTrafficMetricController(AdminTrafficMetricQueryService adminTrafficMetricQueryService) {
        this.adminTrafficMetricQueryService = adminTrafficMetricQueryService;
    }

    @GetMapping
    public ApiResponse<AdminTrafficMetricsResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "metricType", required = false) String metricType,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        return ApiResponse.success(
                adminTrafficMetricQueryService.listMetrics(projectId, from, to, page, pageSize, metricType, userId, deviceId, sessionId)
        );
    }
}
