package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.PrivacyRequestCreatedResponse;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.dto.PrivacyRequestSubmitRequest;
import com.github.analyticshub.service.PrivacyRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyRequestController {

    private final PrivacyRequestService privacyRequestService;

    public PrivacyRequestController(PrivacyRequestService privacyRequestService) {
        this.privacyRequestService = privacyRequestService;
    }

    @PostMapping("/export")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PrivacyRequestCreatedResponse> submitExportRequest(
            @Valid @RequestBody PrivacyRequestSubmitRequest request) {
        return ApiResponse.success(privacyRequestService.submitExportRequest(request));
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PrivacyRequestCreatedResponse> submitDeleteRequest(
            @Valid @RequestBody PrivacyRequestSubmitRequest request) {
        return ApiResponse.success(privacyRequestService.submitDeleteRequest(request));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<PrivacyRequestDetailResponse> getRequest(
            @PathVariable("requestId") String requestId) {
        return ApiResponse.success(privacyRequestService.getRequest(requestId));
    }

    @GetMapping("/requests/latest")
    public ApiResponse<PrivacyRequestDetailResponse> getLatestRequest() {
        return ApiResponse.success(privacyRequestService.getLatestRequest());
    }
}
