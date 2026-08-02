package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminProjectCreateRequest;
import com.github.analyticshub.dto.AdminProjectResponse;
import com.github.analyticshub.dto.AdminProjectUpdateRequest;
import com.github.analyticshub.dto.ProjectConnectionTestResult;
import com.github.analyticshub.dto.ProjectHealthResult;
import com.github.analyticshub.dto.ProjectInitResult;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.service.AdminProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端项目控制器
 * 需要 Admin Token（由 AdminApiAuthenticationFilter 统一校验）
 */
@RestController
@RequestMapping("/api/admin")
public class AdminProjectController {

    private static final System.Logger log = System.getLogger(AdminProjectController.class.getName());

    private final AdminProjectService adminProjectService;

    public AdminProjectController(AdminProjectService adminProjectService) {
        this.adminProjectService = adminProjectService;
    }

    /**
     * 返回全部项目配置，不包含数据库密码等敏感字段。
     */
    @GetMapping("/projects")
    public ApiResponse<List<AdminProjectResponse>> listProjects() {
        List<AnalyticsProject> projects = adminProjectService.listProjects();
        return ApiResponse.success(projects.stream().map(AdminProjectResponse::from).toList());
    }

    /**
     * 创建项目元数据；项目业务库初始化由独立的 init API 显式执行。
     */
    @PostMapping("/projects")
    public ResponseEntity<ApiResponse<AdminProjectResponse>> createProject(
            @Valid @RequestBody AdminProjectCreateRequest request) {
        log.log(System.Logger.Level.INFO, "创建项目请求: {0}", request.projectId());
        AnalyticsProject project = adminProjectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(AdminProjectResponse.from(project)));
    }

    /**
     * 更新项目元数据。projectId 是稳定标识，创建后不允许修改。
     */
    @PutMapping("/projects/{id}")
    public ApiResponse<AdminProjectResponse> updateProject(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminProjectUpdateRequest request) {
        AnalyticsProject project = adminProjectService.updateProject(id, request);
        return ApiResponse.success(AdminProjectResponse.from(project));
    }

    /**
     * 删除 AnalyticsHub 系统库中的项目配置，不删除项目业务库数据。
     */
    @DeleteMapping("/projects/{id}")
    public ApiResponse<Map<String, String>> deleteProject(@PathVariable("id") Long id) {
        AnalyticsProject deleted = adminProjectService.deleteProject(id);
        return ApiResponse.success(Map.of("message", "项目已删除", "projectId", deleted.getProjectId()));
    }

    /**
     * 测试当前项目数据库配置是否可连接，不执行数据库迁移。
     */
    @PostMapping("/projects/{id}/test")
    public ApiResponse<ProjectConnectionTestResult> testProjectConnection(@PathVariable("id") Long id) {
        ProjectConnectionTestResult result = adminProjectService.testConnection(id);
        return ApiResponse.success(result);
    }

    /**
     * 对项目业务库执行受 Flyway 管理的 AnalyticsHub schema migration。
     */
    @PostMapping("/projects/{id}/init")
    public ApiResponse<ProjectInitResult> initProjectDatabase(@PathVariable("id") Long id) {
        ProjectInitResult result = adminProjectService.initializeProjectDatabase(id);
        return ApiResponse.success(result);
    }

    /**
     * 检查项目业务库连接、数据表和迁移版本状态。
     */
    @GetMapping("/projects/{id}/health")
    public ApiResponse<ProjectHealthResult> checkProjectHealth(@PathVariable("id") Long id) {
        ProjectHealthResult result = adminProjectService.checkProjectHealth(id);
        return ApiResponse.success(result);
    }
}
