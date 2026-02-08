package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.service.AdminMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端运营数据接口
 */
@RestController
@RequestMapping("/api/admin/metrics")
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;

    public AdminMetricsController(AdminMetricsService adminMetricsService) {
        this.adminMetricsService = adminMetricsService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminMetricsOverviewResponse> overview(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.success(adminMetricsService.getOverview(projectId, from, to));
    }

    @GetMapping("/trends")
    public ApiResponse<AdminMetricsTrendResponse> trends(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "granularity", required = false) String granularity) {
        return ApiResponse.success(adminMetricsService.getTrends(projectId, from, to, granularity));
    }

    @GetMapping("/top-events")
    public ApiResponse<AdminMetricsTopEventsResponse> topEvents(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(adminMetricsService.getTopEvents(projectId, from, to, limit));
    }
}
