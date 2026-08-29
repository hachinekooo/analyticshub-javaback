package com.github.analyticshub.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {
    
    private final String code;
    private final HttpStatus httpStatus;
    private final Map<String, Object> details;

    public BusinessException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(String code, String message, HttpStatus httpStatus) {
        this(code, message, httpStatus, Map.of());
    }

    public BusinessException(String code, String message, HttpStatus httpStatus, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    // Getters
    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    // 预定义的常见业务异常
    public static BusinessException invalidProject(String projectId) {
        return new BusinessException("INVALID_PROJECT", "无效的项目ID: " + projectId);
    }

    public static BusinessException projectInactive() {
        return new BusinessException("PROJECT_INACTIVE", "项目未激活", HttpStatus.FORBIDDEN);
    }

    public static BusinessException projectNotFound() {
        return new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
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

    public static BusinessException deviceAlreadyRegistered() {
        return new BusinessException(
                "DEVICE_ALREADY_REGISTERED",
                "设备已注册；凭证轮换必须通过受认证的恢复流程",
                HttpStatus.CONFLICT
        );
    }

    public static BusinessException deviceNotFound() {
        return new BusinessException("DEVICE_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND);
    }

    public static BusinessException missingEventType() {
        return new BusinessException("MISSING_EVENT_TYPE", "缺少事件类型");
    }

    public static BusinessException invalidTimestamp() {
        return new BusinessException("INVALID_TIMESTAMP", "时间戳格式无效");
    }

    public static BusinessException invalidSessionId() {
        return new BusinessException("VALIDATION_ERROR", "会话ID格式无效");
    }

    public static BusinessException invalidEventProperties() {
        return new BusinessException("INVALID_EVENT_PROPERTIES", "事件属性无法序列化");
    }

    public static BusinessException eventPropertiesTooLarge(int maxBytes) {
        return new BusinessException(
                "EVENT_PROPERTIES_TOO_LARGE",
                "事件属性不能超过 " + maxBytes + " 字节",
                HttpStatus.CONTENT_TOO_LARGE
        );
    }

    public static BusinessException analyticsQueryRangeExceeded(int maxRangeDays) {
        return new BusinessException(
                "ANALYTICS_QUERY_RANGE_EXCEEDED",
                "分析时间范围过大，请缩短到 " + maxRangeDays + " 天以内",
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }

    public static BusinessException analyticsQueryBudgetExceeded(int maxCandidateRows) {
        return new BusinessException(
                "ANALYTICS_QUERY_BUDGET_EXCEEDED",
                "候选事件超过交互式分析预算（" + maxCandidateRows + " 条），请缩短时间范围或增加筛选条件",
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }

    public static BusinessException analyticsFunnelDimensionBudgetExceeded(
            int maxGroups,
            int maxValueLength
    ) {
        return new BusinessException(
                "ANALYTICS_QUERY_BUDGET_EXCEEDED",
                "漏斗维度超过交互式分析预算（最多 " + maxGroups + " 组，维度值最长 "
                        + maxValueLength + " 字符），请缩短范围或收窄维度",
                HttpStatus.UNPROCESSABLE_CONTENT
        );
    }

    public static BusinessException analysisPackTrustedSchemaConflict(
            String propertyKey,
            String packKey
    ) {
        return new BusinessException(
                "ANALYSIS_PACK_TRUSTED_SCHEMA_CONFLICT",
                "属性 " + propertyKey + " 的修改会破坏 Analysis Pack " + packKey + " 的可信 Schema 策略",
                HttpStatus.CONFLICT
        );
    }

    public static BusinessException analyticsQueryTimedOut() {
        return new BusinessException(
                "ANALYTICS_QUERY_TIMEOUT",
                "分析查询超时，请缩短时间范围或增加筛选条件",
                HttpStatus.REQUEST_TIMEOUT
        );
    }

}
