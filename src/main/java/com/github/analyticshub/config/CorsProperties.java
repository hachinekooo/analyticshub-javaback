package com.github.analyticshub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    private List<String> allowedMethods = new ArrayList<>(List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    ));
    private List<String> allowedHeaders = new ArrayList<>(List.of(
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
            "X-Admin-OTP",
            "X-Traffic-Token"
    ));
    private boolean allowCredentials;
    private long maxAge = 3600;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    public List<String> getAllowedHeaders() {
        return allowedHeaders;
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }
}
