package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminAppVersionDistributionResponse;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTopEventsResponse;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.dto.AnalyticsDataQualityResponse;
import com.github.analyticshub.service.AdminMetricsService;
import com.github.analyticshub.service.AnalyticsDataQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端运营数据接口
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/metrics")
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;
    private final AnalyticsDataQualityService dataQualityService;

    /**
     * 读取概览指标。
     *
     * @param propertyFilters 传事件属性筛选的 JSON 数组；有有效条件时，会话数改按匹配事件的会话 ID 去重，不影响设备库存。
     */
    @GetMapping("/overview")
    public ApiResponse<AdminMetricsOverviewResponse> overview(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(adminMetricsService.getOverview(projectId, from, to, propertyFilters));
    }

    /**
     * 读取事件和去重身份的趋势。
     *
     * @param granularity 时间分桶粒度，支持 {@code day} 或 {@code hour}，默认按天。
     * @param propertyFilters 筛选已登记且允许筛选的事件属性，传 JSON 数组；不传表示不加属性条件。
     */
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

    /**
     * 按事件计数取排行。
     *
     * @param aggregation 默认 {@code raw} 按原始事件名；{@code semantic} 按业务定义归并，未映射事件仍按原名计数。
     * @param propertyFilters 筛选已登记且允许筛选的事件属性，传 JSON 数组；不传表示不加属性条件。
     */
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

    /**
     * 查看活跃设备使用的 App 版本。
     *
     * @param propertyFilters 传事件属性筛选的 JSON 数组；版本和构建号取每台设备最新的匹配事件。
     */
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
