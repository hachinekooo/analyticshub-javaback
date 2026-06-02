package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminPrivacyNotifyRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestUpdateRequest;
import com.github.analyticshub.dto.AdminPrivacyRequestsResponse;
import com.github.analyticshub.dto.PrivacyRequestDetailResponse;
import com.github.analyticshub.service.AdminPrivacyRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/privacy/requests")
public class AdminPrivacyRequestController {

    private final AdminPrivacyRequestService adminPrivacyRequestService;

    public AdminPrivacyRequestController(AdminPrivacyRequestService adminPrivacyRequestService) {
        this.adminPrivacyRequestService = adminPrivacyRequestService;
    }

    @GetMapping
    public ApiResponse<AdminPrivacyRequestsResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "requestType", required = false) String requestType,
            @RequestParam(value = "processor", required = false) String processor,
            @RequestParam(value = "userId", required = false) String userId) {
        return ApiResponse.success(
                adminPrivacyRequestService.listRequests(projectId, from, to, page, pageSize, status, requestType, processor, userId)
        );
    }

    @GetMapping("/{requestId}")
    public ApiResponse<PrivacyRequestDetailResponse> detail(
            @RequestParam("projectId") String projectId,
            @PathVariable("requestId") String requestId) {
        return ApiResponse.success(adminPrivacyRequestService.getRequestDetail(projectId, requestId));
    }

    @PatchMapping("/{requestId}")
    public ApiResponse<PrivacyRequestDetailResponse> update(
            @RequestParam("projectId") String projectId,
            @PathVariable("requestId") String requestId,
            @Valid @RequestBody AdminPrivacyRequestUpdateRequest request) {
        return ApiResponse.success(adminPrivacyRequestService.updateRequest(projectId, requestId, request));
    }

    @PostMapping("/{requestId}/notify")
    public ApiResponse<Map<String, String>> notifyUser(
            @RequestParam("projectId") String projectId,
            @PathVariable("requestId") String requestId,
            @Valid @RequestBody AdminPrivacyNotifyRequest request) {
        return ApiResponse.success(adminPrivacyRequestService.notifyUser(projectId, requestId, request));
    }
}
