package com.github.analyticshub.security;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TwoFactorAuthService {
    private static final Logger log = LoggerFactory.getLogger(TwoFactorAuthService.class);
    
    // 使用字段注入，因为 GoogleAuthenticator 不依赖 Spring
    @Value("${app.security.2fa.secret:}")
    private String secretKey;

    @Value("${app.security.2fa.enabled:false}")
    private boolean enabled;
    
    // 信任的有效期：24小时
    private static final Duration TRUST_DURATION = Duration.ofHours(24);
    
    // 内存存储受信任设备 (IP -> 过期时间)
    private final Map<String, LocalDateTime> trustedDevices = new ConcurrentHashMap<>();
    
    private final GoogleAuthenticator gAuth;

    public TwoFactorAuthService() {
        // 配置 TOTP 参数：每 30 秒变一次，允许前后 3 个窗口（容错 90 秒）
        GoogleAuthenticatorConfig config = new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder()
                .setTimeStepSizeInMillis(30000)
                .setWindowSize(3) 
                .build();
        this.gAuth = new GoogleAuthenticator(config);
    }

    /**
     * 判断是否启用 2FA 且密钥已配置
     */
    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    /**
     * 验证用户输入的 6 位动态码
     */
    public boolean verifyCode(int code) {
        if (!isEnabled()) {
            return true; // 未启用直接通过
        }
        try {
            return gAuth.authorize(secretKey, code);
        } catch (Exception e) {
            log.warn("TOTP 验证异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查当前请求是否来自受信任设备/IP
     * @param clientIp 客户端IP
     * @return true=受信任（无需验证），false=需要验证
     */
    public boolean isTrusted(String clientIp) {
        if (!isEnabled()) {
            return true; // 未启用则全部视为受信任
        }
        
        LocalDateTime expiry = trustedDevices.get(clientIp);
        if (expiry == null) {
            return false;
        }
        
        if (LocalDateTime.now().isAfter(expiry)) {
            trustedDevices.remove(clientIp);
            return false;
        }
        
        return true;
    }

    /**
     * 将当前 IP 加入受信任列表
     */
    public void trustDevice(String clientIp) {
        trustedDevices.put(clientIp, LocalDateTime.now().plus(TRUST_DURATION));
        log.info("IP [{}] 已通过 2FA 验证，加入信任列表，有效期至 {}", clientIp, LocalDateTime.now().plus(TRUST_DURATION));
    }
    
    /**
     * 获取用于绑定的密钥（如果未配置则生成一个新的，方便第一次设置）
     */
    public String getOrGenerateSecret() {
        if (secretKey != null && !secretKey.isBlank()) {
            return secretKey;
        }
        // 如果没有配置，临时生成一个（仅用于展示，重启失效）
        final GoogleAuthenticatorKey key = gAuth.createCredentials();
        return key.getKey();
    }
}
