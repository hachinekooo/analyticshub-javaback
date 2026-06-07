package com.github.analyticshub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.security.AdminApiAuthenticationFilter;
import com.github.analyticshub.security.ApiAuthenticationFilter;
import com.github.analyticshub.security.RateLimitService;
import com.github.analyticshub.service.EmailService;
import com.github.analyticshub.security.TwoFactorAuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration
 * - Disable CSRF for API calls (non-browser clients)
 * - Delegate auth to custom filters (AdminApiAuthenticationFilter / ApiAuthenticationFilter)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final MultiDataSourceManager dataSourceManager;
    private final RateLimitService rateLimitService;
    private final EmailService emailService;
    private final TwoFactorAuthService twoFactorAuthService;
    
    @org.springframework.beans.factory.annotation.Value("${app.security.admin-token:}")
    private String adminToken;

    @org.springframework.beans.factory.annotation.Value("${app.security.signature-validity-ms:300000}")
    private long signatureValidityMs;

    public SecurityConfig(ObjectMapper objectMapper,
                          MultiDataSourceManager dataSourceManager,
                          RateLimitService rateLimitService,
                          EmailService emailService,
                          TwoFactorAuthService twoFactorAuthService) {
        this.objectMapper = objectMapper;
        this.dataSourceManager = dataSourceManager;
        this.rateLimitService = rateLimitService;
        this.emailService = emailService;
        this.twoFactorAuthService = twoFactorAuthService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // 显式添加认证过滤器，顺序很重要
            // 1. 管理端认证（含 2FA）
            .addFilterBefore(adminApiAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            // 2. 采集端认证
            .addFilterAfter(apiAuthenticationFilter(), AdminApiAuthenticationFilter.class);
            
        return http.build();
    }

    @Bean
    public AdminApiAuthenticationFilter adminApiAuthenticationFilter() {
        return new AdminApiAuthenticationFilter(
                objectMapper, 
                rateLimitService, 
                emailService, 
                twoFactorAuthService,
                adminToken
        );
    }

    @Bean
    public ApiAuthenticationFilter apiAuthenticationFilter() {
        return new ApiAuthenticationFilter(
                dataSourceManager, 
                objectMapper, 
                signatureValidityMs
        );
    }

    /**
     * 关闭 AdminApiAuthenticationFilter 的默认注册
     * 避免被 Spring Boot 自动加入到 Servlet 全局过滤器链中
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<AdminApiAuthenticationFilter> adminApiAuthenticationFilterRegistration(AdminApiAuthenticationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<AdminApiAuthenticationFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 关闭 ApiAuthenticationFilter 的默认注册
     * 避免被 Spring Boot 自动加入到 Servlet 全局过滤器链中
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilterRegistration(ApiAuthenticationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<ApiAuthenticationFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
