package com.github.analyticshub.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorInfo error, String timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, Instant.now().toString());
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(code, message, null), Instant.now().toString());
    }

    public static <T> ApiResponse<T> error(String code, String message, Object details) {
        return new ApiResponse<>(false, null, new ErrorInfo(code, message, details), Instant.now().toString());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static record ErrorInfo(String code, String message, Object details) {
        public ErrorInfo(String code, String message) {
            this(code, message, null);
        }
    }
}
