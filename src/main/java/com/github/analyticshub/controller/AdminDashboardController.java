package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminDashboardRecord;
import com.github.analyticshub.dto.AdminDashboardUpsertRequest;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.service.AdminDashboardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/dashboards")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ApiResponse<List<AdminDashboardRecord>> list(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(dashboardService.list(projectId));
    }

    @GetMapping("/{dashboardKey}")
    public ApiResponse<AdminDashboardRecord> get(
            @PathVariable("projectId") String projectId,
            @PathVariable("dashboardKey") String dashboardKey
    ) {
        return ApiResponse.success(dashboardService.get(projectId, dashboardKey));
    }

    @PutMapping("/{dashboardKey}")
    public ApiResponse<AdminDashboardRecord> upsert(
            @PathVariable("projectId") String projectId,
            @PathVariable("dashboardKey") String dashboardKey,
            @Valid @RequestBody AdminDashboardUpsertRequest request
    ) {
        return ApiResponse.success(dashboardService.upsert(projectId, dashboardKey, request));
    }

    @DeleteMapping("/{dashboardKey}")
    public ApiResponse<Void> delete(
            @PathVariable("projectId") String projectId,
            @PathVariable("dashboardKey") String dashboardKey,
            @RequestParam(value = "expectedRevision", required = false) String expectedRevision
    ) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new BusinessException(
                    "DASHBOARD_REVISION_REQUIRED",
                    "删除 dashboard 时必须提供 expectedRevision"
            );
        }
        long parsedRevision;
        try {
            parsedRevision = Long.parseLong(expectedRevision);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    "INVALID_DASHBOARD_REVISION",
                    "expectedRevision 必须是大于 0 的整数"
            );
        }
        dashboardService.delete(projectId, dashboardKey, parsedRevision);
        return ApiResponse.success(null);
    }
}
