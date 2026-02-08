package com.github.analyticshub.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备注册实体
 * 记录设备的认证信息和基本属性
 */
@TableName("analytics_devices")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("device_id")
    private UUID deviceId;

    @TableField("api_key")
    private String apiKey;

    @TableField("secret_key")
    @JsonIgnore
    // Secret key must never be serialized in API responses.
    private String secretKey;

    @TableField("device_model")
    private String deviceModel;

    @TableField("os_version")
    private String osVersion;

    @TableField("app_version")
    private String appVersion;

    @TableField("project_id")
    private String projectId = "analytics-system";

    @TableField("is_banned")
    private Boolean isBanned = false;

    @TableField("ban_reason")
    private String banReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "last_active_at", fill = FieldFill.INSERT_UPDATE)
    private Instant lastActiveAt;

    // 无参构造函数
    public Device() {
    }

    // 全参构造函数
    public Device(Long id, UUID deviceId, String apiKey, String secretKey, String deviceModel,
                  String osVersion, String appVersion, String projectId, Boolean isBanned,
                  String banReason, Instant createdAt, Instant lastActiveAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.deviceModel = deviceModel;
        this.osVersion = osVersion;
        this.appVersion = appVersion;
        this.projectId = projectId;
        this.isBanned = isBanned;
        this.banReason = banReason;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Boolean getIsBanned() {
        return isBanned;
    }

    public void setIsBanned(Boolean isBanned) {
        this.isBanned = isBanned;
    }

    public String getBanReason() {
        return banReason;
    }

    public void setBanReason(String banReason) {
        this.banReason = banReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
