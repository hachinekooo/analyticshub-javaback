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

    public EventService(MultiDataSourceManager dataSourceManager, ObjectMapper objectMapper) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
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
    }
}
