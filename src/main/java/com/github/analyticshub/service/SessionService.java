package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.SessionUploadRequest;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 会话管理服务
 * 处理会话记录和更新
 */
@Service
public class SessionService {

    private static final System.Logger log = System.getLogger(SessionService.class.getName());

    private final MultiDataSourceManager dataSourceManager;

    public SessionService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 上传会话数据
     * 如果会话已存在则更新，否则创建新会话
     */
    @Transactional
    public void uploadSession(SessionUploadRequest request) {
        RequestContext context = RequestContext.get();

        // 1. 验证必需字段
        if (request.sessionId() == null) {
            throw new BusinessException("MISSING_SESSION_ID", "缺少会话ID");
        }

        if (!CryptoUtils.isValidUUID(request.sessionId().toString())) {
            throw BusinessException.invalidSessionId();
        }

        if (request.sessionStartTime() == null) {
            throw new BusinessException("MISSING_SESSION_START_TIME", "缺少会话开始时间");
        }

        // 2. 使用UPSERT存储到数据库
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String sessionsTable = dataSourceManager.getTableName(context.getProjectId(), "sessions");

        String upsertSql = String.format(
                "INSERT INTO %s (session_id, device_id, user_id, session_start_time, session_duration_ms, " +
                        "device_model, os_version, app_version, build_number, screen_count, event_count, project_id, created_at) " +
                        "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (session_id) DO UPDATE SET " +
                        "session_duration_ms = EXCLUDED.session_duration_ms, " +
                        "screen_count = EXCLUDED.screen_count, " +
                        "event_count = EXCLUDED.event_count, " +
                        "device_model = EXCLUDED.device_model, " +
                        "os_version = EXCLUDED.os_version, " +
                        "app_version = EXCLUDED.app_version, " +
                        "build_number = EXCLUDED.build_number",
                sessionsTable
        );

        try {
            jdbcTemplate.update(upsertSql,
                    request.sessionId().toString(),
                    context.getDevice().getDeviceId().toString(),
                    context.getUserId(),
                    request.sessionStartTime(),
                    request.sessionDurationMs() != null ? request.sessionDurationMs() : 0L,
                    request.deviceModel(),
                    request.osVersion(),
                    request.appVersion(),
                    request.buildNumber(),
                    request.screenCount() != null ? request.screenCount() : 0,
                    request.eventCount() != null ? request.eventCount() : 0,
                    context.getProjectId(),
                    Instant.now()
            );

            log.log(System.Logger.Level.INFO, "会话已更新: {0}", request.sessionId());

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "会话上传失败", e);
            throw new RuntimeException("Failed to upload session", e);
        }
    }
}
