package com.github.analyticshub.dto;

import com.github.analyticshub.entity.AnalyticsProject;

import java.time.Instant;

/**
 * 管理端项目响应。
 *
 * <p>API 使用显式 DTO，避免数据库实体字段变化意外扩散到公开契约。</p>
 */
public record AdminProjectResponse(
        Long id,
        String projectId,
        String projectName,
        ProjectAnalysisTemplate analysisTemplate,
        String dbHost,
        Integer dbPort,
        String dbName,
        String dbSchema,
        String dbUser,
        String tablePrefix,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminProjectResponse from(AnalyticsProject project) {
        return new AdminProjectResponse(
                project.getId(),
                project.getProjectId(),
                project.getProjectName(),
                project.getAnalysisTemplate(),
                project.getDbHost(),
                project.getDbPort(),
                project.getDbName(),
                project.getDbSchema(),
                project.getDbUser(),
                project.getTablePrefix(),
                project.getIsActive(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
