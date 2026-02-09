package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.service.CounterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/counters")
public class PublicCounterController {

    private final CounterService counterService;

    public PublicCounterController(CounterService counterService) {
        this.counterService = counterService;
    }

    @GetMapping
    public ApiResponse<CountersResponse> list(@RequestParam("projectId") String projectId) {
        return ApiResponse.success(counterService.list(projectId, true));
    }

    @GetMapping("/{key}")
    public ApiResponse<CounterRecord> get(@RequestParam("projectId") String projectId,
                                          @PathVariable("key") String key) {
        return ApiResponse.success(counterService.get(projectId, key, true));
    }
}
