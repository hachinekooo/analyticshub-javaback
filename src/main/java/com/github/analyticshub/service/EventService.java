package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.dto.EventTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件追踪服务
 * 处理事件记录和查询
 */
@Service
public class EventService {

    private static final System.Logger log = System.getLogger(EventService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final CounterService counterService;

    public EventService(MultiDataSourceManager dataSourceManager, ObjectMapper objectMapper, CounterService counterService) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
    }

    /**
     * 追踪事件
     */
    @Transactional
    public EventTrackResponse trackEvent(EventTrackRequest request) {
        RequestContext context = RequestContext.get();
        
        // 1. 验证必需字段
        if (request.eventType() == null || request.eventType().isBlank()) {
            throw BusinessException.missingEventType();
        }

        if (request.timestamp() == null) {
            throw BusinessException.invalidTimestamp();
        }

        // 2. 验证sessionId格式（如果提供）
        if (request.sessionId() != null && !CryptoUtils.isValidUUID(request.sessionId().toString())) {
            throw BusinessException.invalidSessionId();
        }

        // 3. 生成事件ID
        String eventId = CryptoUtils.generateEventId();

        // 4. 存储到项目数据库
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String eventsTable = dataSourceManager.getTableName(context.getProjectId(), "events");

        String insertSql = String.format(
                "INSERT INTO %s (event_id, device_id, user_id, session_id, event_type, event_timestamp, properties, project_id, created_at) " +
                        "VALUES (?, ?::uuid, ?, ?::uuid, ?, ?, ?::jsonb, ?, ?)",
                eventsTable
        );

        try {
            String propertiesJson = request.properties() != null ?
                    objectMapper.writeValueAsString(request.properties()) : null;
            Instant now = Instant.now();

            jdbcTemplate.update(insertSql,
                    eventId,
                    context.getDevice().getDeviceId().toString(),
                    context.getUserId(),
                    request.sessionId() != null ? request.sessionId().toString() : null,
                    request.eventType(),
                    request.timestamp(),
                    propertiesJson,
                    context.getProjectId(),
                    Timestamp.from(now)
            );

            log.log(System.Logger.Level.INFO, "事件已记录: {0} ({1})", request.eventType(), eventId);

            // 触发计数器自动化 (异步/原子由 CounterService 保证处理)
            try {
                counterService.processEventAutoIncrements(context.getProjectId(), request.eventType(), request.properties());
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "计数器自动维护失败: {0}", e.getMessage());
            }

            return new EventTrackResponse(eventId);

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "事件追踪失败", e);
            throw new RuntimeException("Failed to track event", e);
        }
    }

    /**
     * 批量追踪事件
     */
    @Transactional
    public void trackEventsBatch(EventTrackRequest[] events) {
        if (events == null || events.length == 0) {
            return;
        }

        RequestContext context = RequestContext.get();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getDataSource());
        String eventsTable = dataSourceManager.getTableName(context.getProjectId(), "events");

        StringBuilder valuesSql = new StringBuilder();
        List<Object> args = new ArrayList<>();

        int acceptedCount = 0;
        // Best-effort batch: skip invalid items instead of failing the whole request.
        for (EventTrackRequest event : events) {
            if (event == null) {
                continue;
            }
            if (event.eventType() == null || event.eventType().isBlank()) {
                continue;
            }
            if (event.timestamp() == null) {
                continue;
            }
            if (event.sessionId() != null && !CryptoUtils.isValidUUID(event.sessionId().toString())) {
                continue;
            }

            String eventId = CryptoUtils.generateEventId();
            String propertiesJson;
            try {
                propertiesJson = event.properties() != null ? objectMapper.writeValueAsString(event.properties()) : null;
            } catch (Exception e) {
                continue;
            }

            if (acceptedCount > 0) {
                valuesSql.append(", ");
            }
            valuesSql.append("(?, ?::uuid, ?, ?::uuid, ?, ?, ?::jsonb, ?, ?)");

            args.add(eventId);
            args.add(context.getDevice().getDeviceId().toString());
            args.add(context.getUserId());
            args.add(event.sessionId() != null ? event.sessionId().toString() : null);
            args.add(event.eventType());
            args.add(event.timestamp());
            args.add(propertiesJson);
            args.add(context.getProjectId());
            args.add(Timestamp.from(Instant.now()));

            acceptedCount++;
        }

        if (acceptedCount == 0) {
            return;
        }

        String insertSql = String.format(
                "INSERT INTO %s (event_id, device_id, user_id, session_id, event_type, event_timestamp, properties, project_id, created_at) VALUES %s",
                eventsTable,
                valuesSql
        );

        jdbcTemplate.update(insertSql, args.toArray());

        // 批量触发计数器自动化
        for (EventTrackRequest event : events) {
            if (event == null || event.eventType() == null) continue;
            try {
                counterService.processEventAutoIncrements(context.getProjectId(), event.eventType(), event.properties());
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "批量计数器自动维护失败: {0}", e.getMessage());
            }
        }
    }

    /**
     * 获取项目下所有不重复的事件类型 (用于配置 UI)
     */
    public List<String> getDistinctEventTypes(String projectId) {
        MultiDataSourceManager.ProjectConfig config = dataSourceManager.getProjectConfig(projectId);
        if (config == null) return List.of();
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSourceManager.getDataSource(projectId));
        String table = dataSourceManager.getTableName(projectId, "events");
        
        String sql = String.format("SELECT DISTINCT event_type FROM %s WHERE project_id = ?", table);
        return jdbcTemplate.queryForList(sql, String.class, projectId);
    }
}
