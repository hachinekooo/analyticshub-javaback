package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminDevicesResponse;
import com.github.analyticshub.service.AdminDeviceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端设备查询接口
 */
@RestController
@RequestMapping("/api/admin/devices")
public class AdminDeviceController {

    private final AdminDeviceQueryService adminDeviceQueryService;

    public AdminDeviceController(AdminDeviceQueryService adminDeviceQueryService) {
        this.adminDeviceQueryService = adminDeviceQueryService;
    }

    @GetMapping
    public ApiResponse<AdminDevicesResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "apiKey", required = false) String apiKey,
            @RequestParam(value = "isBanned", required = false) Boolean isBanned) {
        return ApiResponse.success(
                adminDeviceQueryService.listDevices(projectId, from, to, page, pageSize, deviceId, apiKey, isBanned)
        );
    }
}
