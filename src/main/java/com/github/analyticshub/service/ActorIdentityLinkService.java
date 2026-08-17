package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.ActorIdentityLinkRequest;
import com.github.analyticshub.dto.ActorIdentityLinkResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** 保存项目内匿名 actor 到权威云账号 actor 的不可变绑定。 */
@Service
public class ActorIdentityLinkService {

    private final MultiDataSourceManager dataSourceManager;
    private final ProjectTransactionExecutor projectTransactions;

    public ActorIdentityLinkService(
            MultiDataSourceManager dataSourceManager,
            ProjectTransactionExecutor projectTransactions
    ) {
        this.dataSourceManager = dataSourceManager;
        this.projectTransactions = projectTransactions;
    }

    public ActorIdentityLinkResponse link(String projectId, ActorIdentityLinkRequest request) {
        String normalizedProjectId = normalizeProject(projectId);
        if (request.sourceActorId().equals(request.canonicalActorId())) {
            throw conflict("ACTOR_LINK_SELF_REFERENCE", "source actor 不能与 canonical actor 相同");
        }

        DataSource dataSource = requireProjectDataSource(normalizedProjectId);
        String table = dataSourceManager.getTableName(normalizedProjectId, "actor_identity_links");
        String suppressionTable = dataSourceManager.getTableName(
                normalizedProjectId,
                "actor_suppressions"
        );
        return projectTransactions.execute(dataSource, jdbcTemplate ->
                linkInTransaction(jdbcTemplate, table, suppressionTable, normalizedProjectId, request)
        );
    }

    private ActorIdentityLinkResponse linkInTransaction(
            JdbcTemplate jdbcTemplate,
            String table,
            String suppressionTable,
            String projectId,
            ActorIdentityLinkRequest request
    ) {
        // 绑定写入很低频。事务内串行化同一 Project 表的写入，确保“查链 + 插入”在并发下
        // 仍是一个原子规则，避免 A→B 与 B→C 同时通过检查后形成 alias chain。
        jdbcTemplate.execute(String.format("LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE", table));

        if (isSuppressed(jdbcTemplate, suppressionTable, projectId, request.canonicalActorId())) {
            // 终态成功：调用方可安全确认 outbox，不应把已完成隐私工单变成无限重试。
            return new ActorIdentityLinkResponse(
                    request.bindingId(),
                    request.sourceActorId(),
                    request.canonicalActorId(),
                    "suppressed"
            );
        }

        StoredLink byBinding = findByBinding(jdbcTemplate, table, projectId, request.bindingId());
        if (byBinding != null) {
            return requireSamePayload(byBinding, request);
        }

        StoredLink bySource = findBySource(jdbcTemplate, table, projectId, request.sourceActorId());
        if (bySource != null) {
            if (bySource.canonicalActorId().equals(request.canonicalActorId())) {
                return response(bySource, "existing");
            }
            throw conflict("ACTOR_LINK_SOURCE_CONFLICT", "anonymous actor 已绑定其他 canonical actor");
        }

        if (existsAsSource(jdbcTemplate, table, projectId, request.canonicalActorId())
                || existsAsCanonical(jdbcTemplate, table, projectId, request.sourceActorId())) {
            throw conflict("ACTOR_LINK_CHAIN_NOT_ALLOWED", "actor alias 只允许 anonymous 到 cloud 的单层绑定");
        }

        int inserted = jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (binding_id, project_id, source_actor_id, canonical_actor_id, linked_at) "
                                + "VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?) "
                                + "ON CONFLICT DO NOTHING",
                        table
                ),
                request.bindingId().toString(),
                projectId,
                request.sourceActorId().toString(),
                request.canonicalActorId().toString(),
                Timestamp.from(request.linkedAt())
        );
        if (inserted == 1) {
            return new ActorIdentityLinkResponse(
                    request.bindingId(),
                    request.sourceActorId(),
                    request.canonicalActorId(),
                    "created"
            );
        }

        StoredLink raced = findBySource(jdbcTemplate, table, projectId, request.sourceActorId());
        if (raced != null && raced.canonicalActorId().equals(request.canonicalActorId())) {
            return response(raced, "existing");
        }
        StoredLink bindingCollision = findByBinding(jdbcTemplate, table, projectId, request.bindingId());
        if (bindingCollision != null) {
            return requireSamePayload(bindingCollision, request);
        }
        throw conflict("ACTOR_LINK_CONFLICT", "actor 绑定发生并发冲突");
    }

    private StoredLink findByBinding(
            JdbcTemplate jdbcTemplate,
            String table,
            String projectId,
            UUID bindingId
    ) {
        return findOne(
                jdbcTemplate,
                String.format(
                        "SELECT binding_id::text, source_actor_id::text, canonical_actor_id::text, linked_at FROM %s "
                                + "WHERE project_id = ? AND binding_id = ?::uuid",
                        table
                ),
                projectId,
                bindingId.toString()
        );
    }

    private StoredLink findBySource(
            JdbcTemplate jdbcTemplate,
            String table,
            String projectId,
            UUID sourceActorId
    ) {
        return findOne(
                jdbcTemplate,
                String.format(
                        "SELECT binding_id::text, source_actor_id::text, canonical_actor_id::text, linked_at FROM %s "
                                + "WHERE project_id = ? AND source_actor_id = ?::uuid",
                        table
                ),
                projectId,
                sourceActorId.toString()
        );
    }

    private StoredLink findOne(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        List<StoredLink> rows = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new StoredLink(
                        UUID.fromString(resultSet.getString(1)),
                        UUID.fromString(resultSet.getString(2)),
                        UUID.fromString(resultSet.getString(3)),
                        resultSet.getTimestamp(4).toInstant()
                ),
                args
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean existsAsSource(JdbcTemplate jdbcTemplate, String table, String projectId, UUID actorId) {
        return exists(jdbcTemplate, table, projectId, "source_actor_id", actorId);
    }

    private boolean existsAsCanonical(JdbcTemplate jdbcTemplate, String table, String projectId, UUID actorId) {
        return exists(jdbcTemplate, table, projectId, "canonical_actor_id", actorId);
    }

    private boolean isSuppressed(
            JdbcTemplate jdbcTemplate,
            String suppressionTable,
            String projectId,
            UUID canonicalActorId
    ) {
        Boolean result = jdbcTemplate.queryForObject(
                String.format(
                        "SELECT EXISTS (SELECT 1 FROM %s "
                                + "WHERE project_id = ? AND canonical_actor_sha256 = ?)",
                        suppressionTable
                ),
                Boolean.class,
                projectId,
                ActorIdentitySuppression.canonicalHash(canonicalActorId)
        );
        return Boolean.TRUE.equals(result);
    }

    private boolean exists(
            JdbcTemplate jdbcTemplate,
            String table,
            String projectId,
            String column,
            UUID actorId
    ) {
        Boolean result = jdbcTemplate.queryForObject(
                String.format(
                        "SELECT EXISTS (SELECT 1 FROM %s WHERE project_id = ? AND %s = ?::uuid)",
                        table,
                        column
                ),
                Boolean.class,
                projectId,
                actorId.toString()
        );
        return Boolean.TRUE.equals(result);
    }

    private ActorIdentityLinkResponse requireSamePayload(StoredLink existing, ActorIdentityLinkRequest request) {
        if (!existing.sourceActorId().equals(request.sourceActorId())
                || !existing.canonicalActorId().equals(request.canonicalActorId())
                || !existing.linkedAt().equals(request.linkedAt())) {
            throw conflict("ACTOR_LINK_IDEMPOTENCY_CONFLICT", "bindingId 已用于其他 actor 绑定");
        }
        return response(existing, "existing");
    }

    private static ActorIdentityLinkResponse response(StoredLink link, String status) {
        return new ActorIdentityLinkResponse(
                link.bindingId(),
                link.sourceActorId(),
                link.canonicalActorId(),
                status
        );
    }

    private DataSource requireProjectDataSource(String projectId) {
        MultiDataSourceManager.ProjectConfig config;
        try {
            config = dataSourceManager.getProjectConfig(projectId);
        } catch (Exception exception) {
            throw BusinessException.invalidProject(projectId);
        }
        if (config == null) {
            throw BusinessException.invalidProject(projectId);
        }
        if (!Boolean.TRUE.equals(config.isActive())) {
            throw BusinessException.projectInactive();
        }
        try {
            return dataSourceManager.getDataSource(projectId);
        } catch (Exception exception) {
            throw BusinessException.projectDbUnavailable(projectId);
        }
    }

    private static String normalizeProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        return projectId.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private record StoredLink(UUID bindingId, UUID sourceActorId, UUID canonicalActorId, java.time.Instant linkedAt) {
    }
}
