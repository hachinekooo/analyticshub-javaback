package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.dto.EventTrackResponse;
import com.github.analyticshub.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 事件追踪控制器
 * 处理事件记录相关请求
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 单事件上传
     * POST /api/v1/events/track
     */
    @PostMapping("/track")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventTrackResponse> trackEvent(
            @Valid @RequestBody EventTrackRequest request) {
        
        EventTrackResponse response = eventService.trackEvent(request);
        return ApiResponse.success(response);
    }

    /**
     * 批量事件上传
     * POST /api/v1/events/batch
     */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> trackEventsBatch(
            @Valid @RequestBody EventTrackRequest[] events) {
        
        eventService.trackEventsBatch(events);
        return ApiResponse.success(null);
    }
}
