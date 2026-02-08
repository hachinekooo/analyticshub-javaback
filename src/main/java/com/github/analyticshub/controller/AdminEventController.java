package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminEventsResponse;
import com.github.analyticshub.service.AdminEventQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端事件查询接口
 */
@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final AdminEventQueryService adminEventQueryService;

    public AdminEventController(AdminEventQueryService adminEventQueryService) {
        this.adminEventQueryService = adminEventQueryService;
    }

    @GetMapping
    public ApiResponse<AdminEventsResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        return ApiResponse.success(
                adminEventQueryService.listEvents(projectId, from, to, page, pageSize, eventType, userId, deviceId)
        );
    }
}
