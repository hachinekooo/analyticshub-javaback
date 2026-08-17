package com.github.analyticshub.config;

import com.github.analyticshub.common.dto.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.security.AdminApiAuthenticationFilter;
import com.github.analyticshub.security.ActorLinkAuthenticationFilter;
import com.github.analyticshub.security.ApiAuthenticationFilter;
import com.github.analyticshub.security.ClientIpResolver;
import com.github.analyticshub.security.RateLimitService;
import com.github.analyticshub.security.PublicEndpointRateLimitFilter;
import com.github.analyticshub.security.RequestPathSecurityPolicy;
import com.github.analyticshub.security.TwoFactorAuthService;
import com.github.analyticshub.service.EmailService;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.CompositeRequestRejectedHandler;
import org.springframework.security.web.firewall.ObservationMarkingRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Security configuration
 * - Disable CSRF for API calls (non-browser clients)
 * - Delegate management/Admin Token and collection HMAC policies to dedicated filters
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ActorLinkSecurityProperties.class)
public class SecurityConfig {

    static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    private final ObjectMapper objectMapper;
    private final MultiDataSourceManager dataSourceManager;
    private final RateLimitService rateLimitService;
    private final EmailService emailService;
    private final TwoFactorAuthService twoFactorAuthService;
    private final ClientIpResolver clientIpResolver;
    private final ActorLinkSecurityProperties actorLinkSecurityProperties;
    
    @org.springframework.beans.factory.annotation.Value("${app.security.admin-token:}")
    private String adminToken;

    @org.springframework.beans.factory.annotation.Value("${app.security.signature-validity-ms:300000}")
    private long signatureValidityMs;

    @org.springframework.beans.factory.annotation.Value("${app.security.max-request-body-bytes:1048576}")
    private int maxRequestBodyBytes;

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.enabled:true}")
    private boolean publicRateLimitEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.requests:100}")
    private int publicRateLimitRequests;

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.window-ms:60000}")
    private long publicRateLimitWindowMs;

    public SecurityConfig(ObjectMapper objectMapper,
                          MultiDataSourceManager dataSourceManager,
                          RateLimitService rateLimitService,
                          EmailService emailService,
                          TwoFactorAuthService twoFactorAuthService,
                          ClientIpResolver clientIpResolver,
                          ActorLinkSecurityProperties actorLinkSecurityProperties) {
        this.objectMapper = objectMapper;
        this.dataSourceManager = dataSourceManager;
        this.rateLimitService = rateLimitService;
        this.emailService = emailService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.clientIpResolver = clientIpResolver;
        this.actorLinkSecurityProperties = actorLinkSecurityProperties;
    }

    @PostConstruct
    void validateConfiguration() {
        validateAdminTokenConfiguration(adminToken);
        actorLinkSecurityProperties.validate();
    }

    static void validateAdminTokenConfiguration(String configuredToken) {
        if (configuredToken == null || configuredToken.isBlank()) {
            return;
        }
        if (!configuredToken.equals(configuredToken.strip())
                || configuredToken.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "Configured Admin Token must have no surrounding whitespace and be at least 32 characters"
            );
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // 内部 actor-link 必须先于普通采集 HMAC 完成专用服务认证。
            .addFilterBefore(actorLinkAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            // 显式添加认证过滤器，顺序很重要
            // 先注册管理端过滤器的相对顺序，再把匿名入口限流放到它之前。
            .addFilterBefore(adminApiAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            // 1. 匿名写入/校验入口限流
            .addFilterBefore(publicEndpointRateLimitFilter(), AdminApiAuthenticationFilter.class)
            // 2. 管理端与 Actuator 认证由上面的 Admin 过滤器处理
            // 3. 采集端认证
            .addFilterAfter(apiAuthenticationFilter(), AdminApiAuthenticationFilter.class);
            
        return http.build();
    }

    /**
     * Keep Spring Security's strict firewall enabled while returning the same
     * bounded API error shape as the application path policy. The firewall can
     * reject a request before any configured filter runs in a real servlet
     * container, so handling only {@link RequestPathSecurityPolicy} inside the
     * filters would make live responses differ from MockMvc responses.
     */
    @Bean
    public RequestRejectedHandler apiRequestRejectedHandler(
            ObservationRegistry observationRegistry
    ) {
        RequestRejectedHandler jsonHandler = (request, response, exception) -> {
            RequestPathSecurityPolicy.Inspection requestPath =
                    RequestPathSecurityPolicy.inspect(request);
            if (RequestPathSecurityPolicy.rejectIfUnsafe(requestPath, response, objectMapper)) {
                return;
            }

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error(
                            "INVALID_REQUEST",
                            "请求无效"
                    )
            ));
        };
        return new CompositeRequestRejectedHandler(
                new ObservationMarkingRequestRejectedHandler(observationRegistry),
                jsonHandler
        );
    }

    @Bean
    public WebSecurityCustomizer requestRejectedHandlerCustomizer(
            RequestRejectedHandler apiRequestRejectedHandler
    ) {
        return web -> web.requestRejectedHandler(apiRequestRejectedHandler);
    }

    @Bean
    public AdminApiAuthenticationFilter adminApiAuthenticationFilter() {
        return new AdminApiAuthenticationFilter(
                objectMapper, 
                rateLimitService, 
                emailService, 
                twoFactorAuthService,
                clientIpResolver,
                adminToken
        );
    }

    @Bean
    public ApiAuthenticationFilter apiAuthenticationFilter() {
        return new ApiAuthenticationFilter(
                dataSourceManager, 
                objectMapper, 
                signatureValidityMs,
                maxRequestBodyBytes
        );
    }

    @Bean
    public PublicEndpointRateLimitFilter publicEndpointRateLimitFilter() {
        return new PublicEndpointRateLimitFilter(
                objectMapper,
                clientIpResolver,
                publicRateLimitEnabled,
                publicRateLimitRequests,
                publicRateLimitWindowMs
        );
    }

    @Bean
    public ActorLinkAuthenticationFilter actorLinkAuthenticationFilter() {
        return new ActorLinkAuthenticationFilter(objectMapper, actorLinkSecurityProperties);
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

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<PublicEndpointRateLimitFilter> publicEndpointRateLimitFilterRegistration(PublicEndpointRateLimitFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<PublicEndpointRateLimitFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<ActorLinkAuthenticationFilter> actorLinkAuthenticationFilterRegistration(ActorLinkAuthenticationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<ActorLinkAuthenticationFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
