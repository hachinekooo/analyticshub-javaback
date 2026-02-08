package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminProjectCreateRequest;
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

    @GetMapping("/projects")
    public ApiResponse<List<AnalyticsProject>> listProjects() {
        List<AnalyticsProject> projects = adminProjectService.listProjects();
        return ApiResponse.success(projects);
    }

    @PostMapping("/projects")
    public ResponseEntity<ApiResponse<AnalyticsProject>> createProject(
            @Valid @RequestBody AdminProjectCreateRequest request) {
        log.log(System.Logger.Level.INFO, "创建项目请求: {0}", request.projectId());
        AnalyticsProject project = adminProjectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(project));
    }

    @PutMapping("/projects/{id}")
    public ApiResponse<AnalyticsProject> updateProject(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminProjectUpdateRequest request) {
        AnalyticsProject project = adminProjectService.updateProject(id, request);
        return ApiResponse.success(project);
    }

    @DeleteMapping("/projects/{id}")
    public ApiResponse<Map<String, String>> deleteProject(@PathVariable("id") Long id) {
        AnalyticsProject deleted = adminProjectService.deleteProject(id);
        return ApiResponse.success(Map.of("message", "项目已删除", "projectId", deleted.getProjectId()));
    }

    @PostMapping("/projects/{id}/test")
    public ApiResponse<ProjectConnectionTestResult> testProjectConnection(@PathVariable("id") Long id) {
        ProjectConnectionTestResult result = adminProjectService.testConnection(id);
        return ApiResponse.success(result);
    }

    @PostMapping("/projects/{id}/init")
    public ApiResponse<ProjectInitResult> initProjectDatabase(@PathVariable("id") Long id) {
        ProjectInitResult result = adminProjectService.initializeProjectDatabase(id);
        return ApiResponse.success(result);
    }

    @GetMapping("/projects/{id}/health")
    public ApiResponse<ProjectHealthResult> checkProjectHealth(@PathVariable("id") Long id) {
        ProjectHealthResult result = adminProjectService.checkProjectHealth(id);
        return ApiResponse.success(result);
    }
}
