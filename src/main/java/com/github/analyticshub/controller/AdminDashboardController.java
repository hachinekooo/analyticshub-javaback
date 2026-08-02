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

/**
 * 管理项目级声明式 Dashboard。
 *
 * <p>接口只接受经过校验的 widget definition，不存储或执行 HTML、JavaScript 与 SQL。
 * 所有读写都以稳定的 projectId 和 dashboardKey 隔离。</p>
 */
@RestController
@RequestMapping("/api/admin/projects/{projectId}/dashboards")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 返回项目的全部 Dashboard 定义，供前端按分析模板选择工作区。
     */
    @GetMapping
    public ApiResponse<List<AdminDashboardRecord>> list(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(dashboardService.list(projectId));
    }

    /**
     * 返回一个指定工作区的 Dashboard 定义；不存在时由业务异常统一转换为 API 错误。
     */
    @GetMapping("/{dashboardKey}")
    public ApiResponse<AdminDashboardRecord> get(
            @PathVariable("projectId") String projectId,
            @PathVariable("dashboardKey") String dashboardKey
    ) {
        return ApiResponse.success(dashboardService.get(projectId, dashboardKey));
    }

    /**
     * 创建或更新 Dashboard；expectedRevision 用于阻止并发编辑静默覆盖。
     */
    @PutMapping("/{dashboardKey}")
    public ApiResponse<AdminDashboardRecord> upsert(
            @PathVariable("projectId") String projectId,
            @PathVariable("dashboardKey") String dashboardKey,
            @Valid @RequestBody AdminDashboardUpsertRequest request
    ) {
        return ApiResponse.success(dashboardService.upsert(projectId, dashboardKey, request));
    }

    /**
     * 按 revision 删除 Dashboard，避免删除管理员未见过的新版本。
     */
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
