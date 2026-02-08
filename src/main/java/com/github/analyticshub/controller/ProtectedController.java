package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.security.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 受保护的测试接口
 */
@RestController
@RequestMapping("/api/v1/protected")
public class ProtectedController {

    @GetMapping("/test")
    public ApiResponse<Map<String, Object>> testProtected() {
        RequestContext context = RequestContext.get();
        Device device = context.getDevice();
        if (device == null) {
            throw new BusinessException("UNAUTHORIZED", "认证失败", HttpStatus.UNAUTHORIZED);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("message", "认证成功！");
        data.put("deviceId", device.getDeviceId());
        data.put("userId", context.getUserId());
        data.put("deviceModel", device.getDeviceModel());
        data.put("lastActive", device.getLastActiveAt());

        return ApiResponse.success(data);
    }
}
