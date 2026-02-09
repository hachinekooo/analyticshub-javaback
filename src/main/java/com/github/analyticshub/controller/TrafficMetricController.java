package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.dto.TrafficMetricTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.TrafficMetricService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/traffic-metrics")
public class TrafficMetricController {

    private final TrafficMetricService trafficMetricService;

    public TrafficMetricController(TrafficMetricService trafficMetricService) {
        this.trafficMetricService = trafficMetricService;
    }

    @PostMapping("/track")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TrafficMetricTrackResponse> track(
            @Valid @RequestBody TrafficMetricTrackRequest request,
            HttpServletRequest httpServletRequest) {
        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");
        return ApiResponse.success(trafficMetricService.track(request, clientIp, userAgent));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Integer>> batch(
            @Valid @RequestBody TrafficMetricTrackRequest[] items,
            HttpServletRequest httpServletRequest) {
        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");
        if (items == null || items.length == 0) {
            throw new BusinessException("EMPTY_ITEMS", "请求体不能为空");
        }
        int accepted = trafficMetricService.trackBatch(items, clientIp, userAgent);
        if (accepted == 0) {
            throw new BusinessException("NO_VALID_ITEMS", "批量请求中没有可写入的数据（请确认 metricType 字段）");
        }
        return ApiResponse.success(Map.of(
                "received", items.length,
                "accepted", accepted,
                "rejected", items.length - accepted
        ));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
