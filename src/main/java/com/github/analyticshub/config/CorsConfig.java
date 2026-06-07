package com.github.analyticshub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS配置
 * 配置跨域访问策略
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许所有源（生产环境应该配置具体域名）
        config.addAllowedOriginPattern("*");
        
        // 允许的HTTP方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // 允许的请求头
        config.setAllowedHeaders(Arrays.asList(
                "Content-Type",
                "Authorization",
                "X-API-Key",
                "X-Device-ID",
                "X-User-ID",
                "X-Timestamp",
                "X-Signature",
                "X-App-Version",
                "X-Project-ID",
                "X-Admin-Token",
                "X-Traffic-Token"
        ));
        
        // 允许携带凭证（配合 allowedOriginPattern 使用时会回显具体 Origin）
        config.setAllowCredentials(true);
        
        // 预检请求的有效期
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}
