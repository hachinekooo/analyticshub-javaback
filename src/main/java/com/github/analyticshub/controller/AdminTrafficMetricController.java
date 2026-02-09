package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminTrafficMetricsResponse;
import com.github.analyticshub.service.AdminTrafficMetricQueryService;
import com.github.analyticshub.dto.TrafficMetricSummaryResponse;
import com.github.analyticshub.service.TrafficMetricStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.github.analyticshub.dto.TrafficMetricTrendResponse;
import com.github.analyticshub.dto.TrafficMetricTopResponse;

/**
 * 管理端流量指标查询接口
 */
@RestController
@RequestMapping("/api/admin/traffic-metrics")
public class AdminTrafficMetricController {

    private final AdminTrafficMetricQueryService adminTrafficMetricQueryService;
    private final TrafficMetricStatsService trafficMetricStatsService;

    public AdminTrafficMetricController(AdminTrafficMetricQueryService adminTrafficMetricQueryService,
                                        TrafficMetricStatsService trafficMetricStatsService) {
        this.adminTrafficMetricQueryService = adminTrafficMetricQueryService;
        this.trafficMetricStatsService = trafficMetricStatsService;
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

    @GetMapping("/summary")
    public ApiResponse<TrafficMetricSummaryResponse> summary(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.success(trafficMetricStatsService.getSummary(projectId, from, to));
    }

    @GetMapping("/trends")
    public ApiResponse<TrafficMetricTrendResponse> trends(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "granularity", required = false) String granularity) {
        return ApiResponse.success(trafficMetricStatsService.getTrends(projectId, from, to, granularity));
    }

    @GetMapping("/top-pages")
    public ApiResponse<TrafficMetricTopResponse> topPages(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(trafficMetricStatsService.getTopPages(projectId, from, to, limit));
    }

    @GetMapping("/top-referrers")
    public ApiResponse<TrafficMetricTopResponse> topReferrers(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(trafficMetricStatsService.getTopReferrers(projectId, from, to, limit));
    }
}
