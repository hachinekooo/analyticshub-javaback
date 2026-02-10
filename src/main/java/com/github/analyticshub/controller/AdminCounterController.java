package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterIncrementRequest;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.service.CounterService;
import com.github.analyticshub.service.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/counters")
public class AdminCounterController {

    private final CounterService counterService;
    private final EventService eventService;

    public AdminCounterController(CounterService counterService, EventService eventService) {
        this.counterService = counterService;
        this.eventService = eventService;
    }

    @GetMapping
    public ApiResponse<CountersResponse> list(@RequestParam("projectId") String projectId) {
        return ApiResponse.success(counterService.list(projectId, false));
    }

    @GetMapping("/metadata/event-types")
    public ApiResponse<java.util.List<String>> getEventTypes(@RequestParam("projectId") String projectId) {
        return ApiResponse.success(eventService.getDistinctEventTypes(projectId));
    }

    @GetMapping("/{key}")
    public ApiResponse<CounterRecord> get(@RequestParam("projectId") String projectId,
                                          @PathVariable("key") String key) {
        return ApiResponse.success(counterService.get(projectId, key, false));
    }

    @PutMapping("/{key}")
    public ApiResponse<CounterRecord> upsert(@RequestParam("projectId") String projectId,
                                             @PathVariable("key") String key,
                                             @Valid @RequestBody(required = false) CounterUpsertRequest request) {
        return ApiResponse.success(counterService.upsert(projectId, key, request));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@RequestParam("projectId") String projectId,
                                    @PathVariable("key") String key) {
        counterService.delete(projectId, key);
        return ApiResponse.success(null);
    }

    @PostMapping("/{key}/increment")
    public ApiResponse<CounterRecord> increment(@RequestParam("projectId") String projectId,
                                                @PathVariable("key") String key,
                                                @Valid @RequestBody(required = false) CounterIncrementRequest request) {
        long delta = request == null || request.delta() == null ? 1L : request.delta();
        return ApiResponse.success(counterService.increment(projectId, key, delta));
    }
}
