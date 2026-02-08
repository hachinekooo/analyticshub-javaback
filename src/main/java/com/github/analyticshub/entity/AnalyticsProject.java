package com.github.analyticshub.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * 分析项目配置实体
 * 支持多项目共享一个后端服务，每个项目可配置独立数据库
 */
@TableName("analytics_projects")
public class AnalyticsProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("project_id")
    private String projectId;

    @TableField("project_name")
    private String projectName;

    // 数据库配置
    @TableField("db_host")
    private String dbHost;

    @TableField("db_port")
    private Integer dbPort;

    @TableField("db_name")
    private String dbName;

    @TableField("db_user")
    private String dbUser;

    @TableField("db_password_encrypted")
    @JsonIgnore
    // Stored encrypted and excluded from API responses.
    private String dbPasswordEncrypted;

    // 表前缀（避免与业务表名冲突）
    @TableField("table_prefix")
    private String tablePrefix = "analytics_";

    // 项目状态
    @TableField("is_active")
    private Boolean isActive = true;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    // 无参构造函数
    public AnalyticsProject() {
    }

    // 全参构造函数
    public AnalyticsProject(Long id, String projectId, String projectName, String dbHost, 
                           Integer dbPort, String dbName, String dbUser, String dbPasswordEncrypted,
                           String tablePrefix, Boolean isActive, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.projectName = projectName;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPasswordEncrypted = dbPasswordEncrypted;
        this.tablePrefix = tablePrefix;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getDbHost() {
        return dbHost;
    }

    public void setDbHost(String dbHost) {
        this.dbHost = dbHost;
    }

    public Integer getDbPort() {
        return dbPort;
    }

    public void setDbPort(Integer dbPort) {
        this.dbPort = dbPort;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPasswordEncrypted() {
        return dbPasswordEncrypted;
    }

    public void setDbPasswordEncrypted(String dbPasswordEncrypted) {
        this.dbPasswordEncrypted = dbPasswordEncrypted;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
