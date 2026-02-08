package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminSessionsResponse;
import com.github.analyticshub.service.AdminSessionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端会话查询接口
 */
@RestController
@RequestMapping("/api/admin/sessions")
public class AdminSessionController {

    private final AdminSessionQueryService adminSessionQueryService;

    public AdminSessionController(AdminSessionQueryService adminSessionQueryService) {
        this.adminSessionQueryService = adminSessionQueryService;
    }

    @GetMapping
    public ApiResponse<AdminSessionsResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        return ApiResponse.success(
                adminSessionQueryService.listSessions(projectId, from, to, page, pageSize, sessionId, userId, deviceId)
        );
    }
}
