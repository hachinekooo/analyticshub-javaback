package com.github.analyticshub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS配置
 * 配置跨域访问策略
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    private final CorsProperties properties;

    public CorsConfig(CorsProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = createConfiguration(properties);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    static CorsConfiguration createConfiguration(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(properties.getAllowedOrigins());
        config.setAllowedMethods(properties.getAllowedMethods());
        config.setAllowedHeaders(properties.getAllowedHeaders());
        config.setAllowCredentials(properties.isAllowCredentials());
        config.setMaxAge(properties.getMaxAge());
        return config;
    }
}
