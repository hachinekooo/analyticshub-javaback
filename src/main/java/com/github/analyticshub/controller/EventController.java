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

    private static final System.Logger log = System.getLogger(EventController.class.getName());

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
        
        log.log(System.Logger.Level.DEBUG, "事件追踪请求: eventType={0}", request.eventType());
        
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
        
        log.log(System.Logger.Level.DEBUG, "批量事件追踪请求: count={0}", events.length);
        
        eventService.trackEventsBatch(events);
        return ApiResponse.success(null);
    }
}
