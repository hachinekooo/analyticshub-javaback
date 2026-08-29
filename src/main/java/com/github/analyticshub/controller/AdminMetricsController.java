package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminAppVersionDistributionResponse;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.dto.AnalyticsDataQualityResponse;
import com.github.analyticshub.service.AdminMetricsService;
import com.github.analyticshub.service.AnalyticsDataQualityService;
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
    private final AnalyticsDataQualityService dataQualityService;

    public AdminMetricsController(
            AdminMetricsService adminMetricsService,
            AnalyticsDataQualityService dataQualityService
    ) {
        this.adminMetricsService = adminMetricsService;
        this.dataQualityService = dataQualityService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminMetricsOverviewResponse> overview(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(adminMetricsService.getOverview(projectId, from, to, propertyFilters));
    }

    @GetMapping("/trends")
    public ApiResponse<AdminMetricsTrendResponse> trends(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "granularity", required = false) String granularity,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(adminMetricsService.getTrends(
                projectId, from, to, granularity, propertyFilters
        ));
    }

    @GetMapping("/top-events")
    public ApiResponse<AdminMetricsTopEventsResponse> topEvents(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "aggregation", required = false) String aggregation,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(
                adminMetricsService.getTopEvents(projectId, from, to, limit, aggregation, propertyFilters)
        );
    }

    @GetMapping("/app-versions")
    public ApiResponse<AdminAppVersionDistributionResponse> appVersions(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(
                adminMetricsService.getAppVersionDistribution(projectId, from, to, propertyFilters)
        );
    }

    @GetMapping("/data-quality")
    public ApiResponse<AnalyticsDataQualityResponse> dataQuality(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.success(dataQualityService.inspect(projectId, from, to));
    }
}
