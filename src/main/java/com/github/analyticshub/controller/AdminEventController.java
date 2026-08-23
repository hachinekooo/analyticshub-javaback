package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminEventsResponse;
import com.github.analyticshub.dto.AdminEventJourneyResponse;
import com.github.analyticshub.dto.AdminEventPropertiesResponse;
import com.github.analyticshub.service.AdminEventJourneyService;
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
    private final AdminEventJourneyService adminEventJourneyService;

    public AdminEventController(AdminEventQueryService adminEventQueryService,
                                AdminEventJourneyService adminEventJourneyService) {
        this.adminEventQueryService = adminEventQueryService;
        this.adminEventJourneyService = adminEventJourneyService;
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
            @RequestParam(value = "resolvedActorId", required = false) String resolvedActorId,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        return ApiResponse.success(
                adminEventQueryService.listEvents(
                        projectId, from, to, page, pageSize, eventType, userId, resolvedActorId, deviceId
                )
        );
    }

    @GetMapping("/journey")
    public ApiResponse<AdminEventJourneyResponse> journey(
            @RequestParam("projectId") String projectId,
            @RequestParam("anchorEventId") String anchorEventId,
            @RequestParam(value = "beforeMinutes", required = false) Integer beforeMinutes,
            @RequestParam(value = "afterMinutes", required = false) Integer afterMinutes) {
        return ApiResponse.success(
                adminEventJourneyService.getJourney(
                        projectId, anchorEventId, beforeMinutes, afterMinutes
                )
        );
    }

    @GetMapping("/properties")
    public ApiResponse<AdminEventPropertiesResponse> properties(
            @RequestParam("projectId") String projectId,
            @RequestParam("eventId") String eventId) {
        return ApiResponse.success(adminEventJourneyService.getEventProperties(projectId, eventId));
    }
}
