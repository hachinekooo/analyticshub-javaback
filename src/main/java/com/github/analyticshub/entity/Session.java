package com.github.analyticshub.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话记录实体
 * 记录用户会话信息
 */
@TableName("analytics_sessions")
public class Session {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private UUID sessionId;

    @TableField("device_id")
    private UUID deviceId;

    @TableField("user_id")
    private String userId;

    @TableField("session_start_time")
    private Instant sessionStartTime;

    @TableField("session_duration_ms")
    private Long sessionDurationMs = 0L;

    @TableField("device_model")
    private String deviceModel;

    @TableField("os_version")
    private String osVersion;

    @TableField("app_version")
    private String appVersion;

    @TableField("build_number")
    private String buildNumber;

    @TableField("screen_count")
    private Integer screenCount = 0;

    @TableField("event_count")
    private Integer eventCount = 0;

    @TableField("project_id")
    private String projectId = "analytics-system";

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    // 无参构造函数
    public Session() {
    }

    // 全参构造函数
    public Session(Long id, UUID sessionId, UUID deviceId, String userId, Instant sessionStartTime,
                   Long sessionDurationMs, String deviceModel, String osVersion, String appVersion,
                   String buildNumber, Integer screenCount, Integer eventCount, String projectId,
                   Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.userId = userId;
        this.sessionStartTime = sessionStartTime;
        this.sessionDurationMs = sessionDurationMs;
        this.deviceModel = deviceModel;
        this.osVersion = osVersion;
        this.appVersion = appVersion;
        this.buildNumber = buildNumber;
        this.screenCount = screenCount;
        this.eventCount = eventCount;
        this.projectId = projectId;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getSessionStartTime() {
        return sessionStartTime;
    }

    public void setSessionStartTime(Instant sessionStartTime) {
        this.sessionStartTime = sessionStartTime;
    }

    public Long getSessionDurationMs() {
        return sessionDurationMs;
    }

    public void setSessionDurationMs(Long sessionDurationMs) {
        this.sessionDurationMs = sessionDurationMs;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    public void setBuildNumber(String buildNumber) {
        this.buildNumber = buildNumber;
    }

    public Integer getScreenCount() {
        return screenCount;
    }

    public void setScreenCount(Integer screenCount) {
        this.screenCount = screenCount;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
