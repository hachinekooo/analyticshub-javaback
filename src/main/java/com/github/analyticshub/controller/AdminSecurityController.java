package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.security.TwoFactorAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/security/2fa")
public class AdminSecurityController {

    private final TwoFactorAuthService twoFactorAuthService;

    public AdminSecurityController(TwoFactorAuthService twoFactorAuthService) {
        this.twoFactorAuthService = twoFactorAuthService;
    }

    /**
     * 获取 2FA 配置信息（用于首次绑定）
     * 如果已启用，返回当前配置；如果未启用，生成临时配置供绑定
     */
    @GetMapping("/setup")
    public ApiResponse<Map<String, String>> setup2FA() {
        String secret = twoFactorAuthService.getOrGenerateSecret();
        String accountName = "AnalyticsHub-Admin";
        String issuer = "AnalyticsHub";
        
        // 生成 otpauth URL (手动拼装，无需依赖 heavy lib)
        String otpAuthUrl = String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s",
                issuer, accountName, secret, issuer);

        Map<String, String> result = new HashMap<>();
        result.put("secret", secret);
        result.put("otpAuthUrl", otpAuthUrl);
        result.put("status", twoFactorAuthService.isEnabled() ? "enabled" : "disabled");
        result.put("instruction", "请将 secret 添加到 Authenticator App，或者将 otpAuthUrl 生成二维码扫码。配置完成后，设置环境变量 APP_SECURITY_2FA_SECRET=<secret> 并重启服务。");

        return ApiResponse.success(result);
    }
}
