package com.github.analyticshub.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {
    
    private final String code;
    private final HttpStatus httpStatus;

    public BusinessException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(String code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    // Getters
    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    // 预定义的常见业务异常
    public static BusinessException invalidProject(String projectId) {
        return new BusinessException("INVALID_PROJECT", "无效的项目ID: " + projectId);
    }

    public static BusinessException projectInactive() {
        return new BusinessException("PROJECT_INACTIVE", "项目未激活", HttpStatus.FORBIDDEN);
    }

    public static BusinessException projectDbUnavailable(String projectId) {
        return new BusinessException(
                "PROJECT_DB_UNAVAILABLE",
                "项目数据库不可用，请检查项目数据库配置: " + projectId,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public static BusinessException missingDeviceId() {
        return new BusinessException("MISSING_DEVICE_ID", "缺少设备ID");
    }

    public static BusinessException invalidDeviceId() {
        return new BusinessException("INVALID_DEVICE_ID", "设备ID格式无效，必须是有效的UUID");
    }

    public static BusinessException missingEventType() {
        return new BusinessException("MISSING_EVENT_TYPE", "缺少事件类型");
    }

    public static BusinessException invalidTimestamp() {
        return new BusinessException("INVALID_TIMESTAMP", "时间戳格式无效");
    }

    public static BusinessException invalidSessionId() {
        return new BusinessException("INVALID_SESSION_ID", "会话ID格式无效");
    }
}
