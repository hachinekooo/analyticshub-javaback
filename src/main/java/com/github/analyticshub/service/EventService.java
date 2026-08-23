package com.github.analyticshub.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.dto.EventTrackResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.logging.LogValueSanitizer;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 事件采集服务。
 *
 * <p>事件、幂等占位和同步派生结果必须在同一个项目数据库事务中提交。
 * 系统数据库的默认事务管理器不适用于动态项目数据源。</p>
 */
@Service
public class EventService {

    private static final System.Logger log = System.getLogger(EventService.class.getName());
    static final int MAX_BATCH_SIZE = 100;

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final CounterService counterService;
    private final ProjectTransactionExecutor projectTransactions;
    private final EventMetadataSchemaSupport metadataSchemaSupport;

    public EventService(
            MultiDataSourceManager dataSourceManager,
            ObjectMapper objectMapper,
            CounterService counterService,
            ProjectTransactionExecutor projectTransactions,
            EventMetadataSchemaSupport metadataSchemaSupport
    ) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.projectTransactions = projectTransactions;
        this.metadataSchemaSupport = metadataSchemaSupport;
    }

    /**
     * 写入单条事件；相同项目、相同幂等键始终返回第一次写入的事件 ID。
     */
    public EventTrackResponse trackEvent(EventTrackRequest request) {
        RequestContext context = requireRequestContext();
        PreparedEvent event = prepareEvent(request);
        String eventsTable = dataSourceManager.getTableName(context.getProjectId(), "events");
        String idempotencyTable = dataSourceManager.getTableName(context.getProjectId(), "idempotency_keys");

        EventWriteResult result = projectTransactions.execute(
                context.getDataSource(),
                jdbcTemplate -> writeEvent(
                        jdbcTemplate,
                        context,
                        eventsTable,
                        idempotencyTable,
                        event,
                        metadataSchemaSupport.supportsMetadataColumns(
                                jdbcTemplate, context.getProjectId(), eventsTable
                        )
                )
        );

        String eventTypeForLog = LogValueSanitizer.eventType(event.eventType());
        if (result.inserted()) {
            log.log(System.Logger.Level.INFO, "事件已记录: {0} ({1})", eventTypeForLog, result.eventId());
        } else {
            log.log(System.Logger.Level.INFO, "事件幂等命中: {0}", eventTypeForLog);
        }
        return new EventTrackResponse(result.eventId());
    }

    /**
     * Best-effort validation（逐项校验）+ atomic persistence（批次原子落库）。
     * 格式无效的条目会跳过；所有有效条目及其派生结果则一起提交或一起回滚。
     */
    public void trackEventsBatch(EventTrackRequest[] events) {
        if (events == null || events.length == 0) {
            return;
        }
        if (events.length > MAX_BATCH_SIZE) {
            throw new BusinessException(
                    "EVENT_BATCH_TOO_LARGE",
                    "单次最多上报 " + MAX_BATCH_SIZE + " 条事件",
                    HttpStatus.CONTENT_TOO_LARGE
            );
        }

        RequestContext context = requireRequestContext();
        List<PreparedEvent> preparedEvents = new ArrayList<>(events.length);
        for (int index = 0; index < events.length; index++) {
            EventTrackRequest event = events[index];
            if (event == null) {
                continue;
            }
            try {
                preparedEvents.add(prepareEvent(event));
            } catch (BusinessException exception) {
                log.log(System.Logger.Level.WARNING,
                        "批量事件条目已跳过: index={0}, code={1}",
                        index,
                        LogValueSanitizer.errorCode(exception.getCode()));
            }
        }
        if (preparedEvents.isEmpty()) {
            return;
        }

        String eventsTable = dataSourceManager.getTableName(context.getProjectId(), "events");
        String idempotencyTable = dataSourceManager.getTableName(context.getProjectId(), "idempotency_keys");
        Integer acceptedCount = projectTransactions.execute(context.getDataSource(), jdbcTemplate -> {
            boolean supportsMetadata = metadataSchemaSupport.supportsMetadataColumns(
                    jdbcTemplate, context.getProjectId(), eventsTable
            );
            int accepted = 0;
            for (PreparedEvent event : preparedEvents) {
                EventWriteResult result = writeEvent(
                        jdbcTemplate,
                        context,
                        eventsTable,
                        idempotencyTable,
                        event,
                        supportsMetadata
                );
                if (result.inserted()) {
                    accepted++;
                }
            }
            return accepted;
        });
        log.log(System.Logger.Level.INFO,
                "批量事件已处理: received={0}, valid={1}, accepted={2}",
                events.length, preparedEvents.size(), acceptedCount == null ? 0 : acceptedCount);
    }

    private EventWriteResult writeEvent(
            JdbcTemplate jdbcTemplate,
            RequestContext context,
            String eventsTable,
            String idempotencyTable,
            PreparedEvent event,
            boolean supportsMetadata
    ) {
        String existingEventId = reserveIdempotencyKey(
                jdbcTemplate,
                context,
                eventsTable,
                idempotencyTable,
                event
        );
        if (existingEventId != null) {
            return new EventWriteResult(existingEventId, false);
        }

        if (supportsMetadata) {
            writeV8Event(jdbcTemplate, context, eventsTable, event);
        } else {
            writeLegacyEvent(jdbcTemplate, context, eventsTable, event);
        }

        // Counter 仍是同步投影；失败必须回滚事件，不能留下永久不一致。
        counterService.processEventAutoIncrements(
                context.getProjectId(),
                event.eventType(),
                event.properties()
        );
        return new EventWriteResult(event.eventId(), true);
    }

    private void writeV8Event(JdbcTemplate jdbcTemplate,
                              RequestContext context,
                              String eventsTable,
                              PreparedEvent event) {
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (event_id, device_id, user_id, session_id, event_type, "
                                + "event_timestamp, properties, properties_size_bytes, identity_scope, "
                                + "project_id, created_at) "
                                + "VALUES (?, ?::uuid, ?, ?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                        eventsTable
                ),
                event.eventId(), context.getDevice().getDeviceId().toString(), context.getUserId(),
                event.sessionId(), event.eventType(), event.timestamp(), event.propertiesJson(),
                event.propertiesSizeBytes(), event.identityScope(), context.getProjectId(),
                Timestamp.from(Instant.now())
        );
    }

    private void writeLegacyEvent(JdbcTemplate jdbcTemplate,
                                  RequestContext context,
                                  String eventsTable,
                                  PreparedEvent event) {
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (event_id, device_id, user_id, session_id, event_type, "
                                + "event_timestamp, properties, project_id, created_at) "
                                + "VALUES (?, ?::uuid, ?, ?::uuid, ?, ?, ?::jsonb, ?, ?)",
                        eventsTable
                ),
                event.eventId(), context.getDevice().getDeviceId().toString(), context.getUserId(),
                event.sessionId(), event.eventType(), event.timestamp(), event.propertiesJson(),
                context.getProjectId(), Timestamp.from(Instant.now())
        );
    }

    /**
     * @return 已存在的事件 ID；返回 null 表示本次请求已取得写入资格。
     */
    private String reserveIdempotencyKey(
            JdbcTemplate jdbcTemplate,
            RequestContext context,
            String eventsTable,
            String idempotencyTable,
            PreparedEvent event
    ) {
        if (event.idempotencyKey() == null) {
            return null;
        }

        String projectId = context.getProjectId();
        String actorId = context.getUserId() == null || context.getUserId().isBlank()
                ? context.getDevice().getDeviceId().toString()
                : context.getUserId();
        // 幂等约束描述的是“同一 actor 的同一业务事件”，不能把客户端键提升为 Project 全局唯一。
        // eventType 同时进入摘要，避免通用采集方误把同一个原始键复用于不同事件时互相吞并。
        String scopedKey = actorId + "\0" + event.eventType() + "\0" + event.idempotencyKey();
        String keyHash = CryptoUtils.sha256Hex(scopedKey);
        String insertSql = String.format(
                "INSERT INTO %s (project_id, key_hash, request_hash, event_id) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (project_id, key_hash) DO NOTHING",
                idempotencyTable
        );
        int inserted = jdbcTemplate.update(
                insertSql,
                projectId,
                keyHash,
                event.requestHash(),
                event.eventId()
        );
        if (inserted == 1) {
            return null;
        }

        String selectSql = String.format(
                "SELECT event_id, request_hash FROM %s " +
                        "WHERE project_id = ? AND key_hash = ? FOR UPDATE",
                idempotencyTable
        );
        List<IdempotencyReservation> reservations = jdbcTemplate.query(
                selectSql,
                (resultSet, rowNumber) -> new IdempotencyReservation(
                        resultSet.getString("event_id"),
                        resultSet.getString("request_hash")
                ),
                projectId,
                keyHash
        );
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Idempotency reservation disappeared during transaction");
        }

        IdempotencyReservation reservation = reservations.getFirst();
        String existingEventId = reservation.eventId();
        String existsSql = String.format("SELECT EXISTS (SELECT 1 FROM %s WHERE event_id = ?)", eventsTable);
        Boolean eventExists = jdbcTemplate.queryForObject(existsSql, Boolean.class, existingEventId);
        if (Boolean.TRUE.equals(eventExists)) {
            return existingEventId;
        }

        // 兼容修复早期非原子实现可能留下的孤儿占位，避免返回不存在的 eventId。
        String repairSql = String.format(
                "UPDATE %s SET event_id = ?, request_hash = ?, created_at = NOW() " +
                        "WHERE project_id = ? AND key_hash = ? AND event_id = ?",
                idempotencyTable
        );
        int repaired = jdbcTemplate.update(
                repairSql,
                event.eventId(),
                event.requestHash(),
                projectId,
                keyHash,
                existingEventId
        );
        if (repaired != 1) {
            throw new IllegalStateException("Failed to repair orphan idempotency reservation");
        }
        log.log(System.Logger.Level.WARNING, "已修复孤立的事件幂等占位: projectId={0}",
                LogValueSanitizer.projectId(projectId));
        return null;
    }

    private PreparedEvent prepareEvent(EventTrackRequest request) {
        if (request == null || request.eventType() == null || request.eventType().isBlank()) {
            throw BusinessException.missingEventType();
        }
        if (request.eventType().length() > 100) {
            throw new BusinessException("VALIDATION_ERROR", "事件类型不能超过100个字符");
        }
        if (request.timestamp() == null) {
            throw BusinessException.invalidTimestamp();
        }
        if (request.sessionId() != null && !CryptoUtils.isValidUUID(request.sessionId().toString())) {
            throw BusinessException.invalidSessionId();
        }

        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        String propertiesJson;
        try {
            propertiesJson = request.properties() == null
                    ? null
                    : objectMapper.writeValueAsString(request.properties());
        } catch (JacksonException exception) {
            throw BusinessException.invalidEventProperties();
        }
        String requestHash = idempotencyKey == null
                ? null
                : createRequestHash(request, propertiesJson);
        int propertiesSizeBytes = propertiesJson == null
                ? 0
                : propertiesJson.getBytes(StandardCharsets.UTF_8).length;
        Object identityScopeValue = request.properties() == null
                ? null
                : request.properties().get("identity_scope");
        String identityScope = identityScopeValue instanceof String value && value.length() <= 64
                ? value
                : null;

        return new PreparedEvent(
                CryptoUtils.generateEventId(),
                request.eventType(),
                request.timestamp(),
                request.properties(),
                propertiesJson,
                propertiesSizeBytes,
                identityScope,
                request.sessionId() == null ? null : request.sessionId().toString(),
                idempotencyKey,
                requestHash
        );
    }

    private String createRequestHash(EventTrackRequest request, String propertiesJson) {
        try {
            ObjectNode fingerprint = objectMapper.createObjectNode();
            fingerprint.put("eventType", request.eventType());
            fingerprint.put("timestamp", request.timestamp());
            if (request.sessionId() == null) {
                fingerprint.putNull("sessionId");
            } else {
                fingerprint.put("sessionId", request.sessionId().toString());
            }
            if (propertiesJson == null) {
                fingerprint.putNull("properties");
            } else {
                fingerprint.set("properties", canonicalize(objectMapper.readTree(propertiesJson)));
            }
            return CryptoUtils.sha256Hex(objectMapper.writeValueAsString(fingerprint));
        } catch (JacksonException exception) {
            throw BusinessException.invalidEventProperties();
        }
    }

    /**
     * JSON object keys are sorted recursively so logically identical property maps
     * produce the same idempotency fingerprint regardless of map iteration order.
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.propertyNames().forEach(fieldNames::add);
            fieldNames.sort(Comparator.naturalOrder());
            for (String fieldName : fieldNames) {
                sorted.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(element -> array.add(canonicalize(element)));
            return array;
        }
        return node.deepCopy();
    }

    private static String normalizeIdempotencyKey(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalized = rawValue.strip();
        if (normalized.length() > 256) {
            throw new BusinessException("VALIDATION_ERROR", "幂等键不能超过256个字符");
        }
        return normalized;
    }

    private static RequestContext requireRequestContext() {
        RequestContext context = RequestContext.get();
        if (context.getProjectId() == null
                || context.getDevice() == null
                || context.getDevice().getDeviceId() == null
                || context.getDataSource() == null) {
            throw new IllegalStateException("Authenticated project request context is incomplete");
        }
        return context;
    }

    /**
     * 获取项目下所有不重复的事件类型，供管理端配置语义映射时使用。
     */
    public List<String> getDistinctEventTypes(String projectId) {
        MultiDataSourceManager.ProjectConfig config = dataSourceManager.getProjectConfig(projectId);
        if (config == null) {
            return List.of();
        }

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSourceManager.getDataSource(projectId));
        String table = dataSourceManager.getTableName(projectId, "events");
        String sql = String.format("SELECT DISTINCT event_type FROM %s WHERE project_id = ?", table);
        return jdbcTemplate.queryForList(sql, String.class, projectId);
    }

    private record PreparedEvent(
            String eventId,
            String eventType,
            Long timestamp,
            Map<String, Object> properties,
            String propertiesJson,
            int propertiesSizeBytes,
            String identityScope,
            String sessionId,
            String idempotencyKey,
            String requestHash
    ) {
    }

    private record IdempotencyReservation(String eventId, String requestHash) {
    }

    private record EventWriteResult(String eventId, boolean inserted) {
    }
}
