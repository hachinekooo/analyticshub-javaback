package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminRetentionResponse;
import com.github.analyticshub.service.AdminProductAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端产品分析接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/analytics")
public class AdminProductAnalyticsController {

    private final AdminProductAnalyticsService analyticsService;

    /**
     * 统计按顺序完成各步骤的去重身份数；指定流程标识字段后，按同一身份下的不同流程分别计数。
     *
     * @param steps 按业务顺序传逗号分隔的事件定义键，去重后需有 2 至 12 项。
     * @param groupBy 分组属性；项目有属性字典时须允许分组，取每个统计主体首次有效的首步属性值。
     * @param journeyKey 流程标识属性；项目有属性字典时须允许串联流程，按归一身份和非空字符串流程标识计数。
     * @param propertyFilters 筛选已登记且允许筛选的事件属性，传 JSON 数组；不传表示不加属性条件。
     */
    @GetMapping("/funnel")
    public ApiResponse<AdminFunnelResponse> funnel(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam("steps") String steps,
            @RequestParam(value = "groupBy", required = false) String groupBy,
            @RequestParam(value = "journeyKey", required = false) String journeyKey,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(analyticsService.getFunnel(
                projectId, from, to, steps, groupBy, journeyKey, propertyFilters
        ));
    }

    /**
     * 以所选范围内的起点事件确定观察人群；回访允许发生在筛选结束之后。
     *
     * @param cohortEvent 确定观察人群的起点事件定义键。
     * @param returnEvent 判断是否回访的事件定义键，可与起点事件相同。
     * @param days 逗号分隔的观察日（0 至 90），以入组的 UTC 当日为第 0 天，默认 1、7、30；回访须发生在入组事件之后。
     * @param propertyFilters 应用到起点和回访候选事件的属性条件，传 JSON 数组。
     */
    @GetMapping("/retention")
    public ApiResponse<AdminRetentionResponse> retention(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam("cohortEvent") String cohortEvent,
            @RequestParam("returnEvent") String returnEvent,
            @RequestParam(value = "days", required = false) String days,
            @RequestParam(value = "propertyFilters", required = false) String propertyFilters) {
        return ApiResponse.success(analyticsService.getRetention(
                projectId, from, to, cohortEvent, returnEvent, days, propertyFilters
        ));
    }
}
