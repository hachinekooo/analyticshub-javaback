package com.github.analyticshub.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件追踪实体
 * 记录用户行为事件
 */
@TableName("analytics_events")
public class Event {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("device_id")
    private UUID deviceId;

    @TableField("user_id")
    private String userId;

    @TableField("session_id")
    private UUID sessionId;

    @TableField("event_type")
    private String eventType;

    @TableField("event_timestamp")
    private Long eventTimestamp;

    @TableField("properties")
    private String properties;

    @TableField("project_id")
    private String projectId = "analytics-system";

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    // 无参构造函数
    public Event() {
    }

    // 全参构造函数
    public Event(Long id, String eventId, UUID deviceId, String userId, UUID sessionId,
                 String eventType, Long eventTimestamp, String properties, String projectId,
                 Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.deviceId = deviceId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.eventTimestamp = eventTimestamp;
        this.properties = properties;
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

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Long eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public String getProperties() {
        return properties;
    }

    public void setProperties(String properties) {
        this.properties = properties;
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
