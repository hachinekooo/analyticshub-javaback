package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminRetentionResponse;
import com.github.analyticshub.service.AdminProductAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端产品分析接口。
 */
@RestController
@RequestMapping("/api/admin/analytics")
public class AdminProductAnalyticsController {

    private final AdminProductAnalyticsService analyticsService;

    public AdminProductAnalyticsController(AdminProductAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/funnel")
    public ApiResponse<AdminFunnelResponse> funnel(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam("steps") String steps,
            @RequestParam(value = "groupBy", required = false) String groupBy) {
        return ApiResponse.success(analyticsService.getFunnel(projectId, from, to, steps, groupBy));
    }

    @GetMapping("/retention")
    public ApiResponse<AdminRetentionResponse> retention(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam("cohortEvent") String cohortEvent,
            @RequestParam("returnEvent") String returnEvent,
            @RequestParam(value = "days", required = false) String days) {
        return ApiResponse.success(analyticsService.getRetention(projectId, from, to, cohortEvent, returnEvent, days));
    }
}
