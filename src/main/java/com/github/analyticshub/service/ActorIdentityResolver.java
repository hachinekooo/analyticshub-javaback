package com.github.analyticshub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一解析 Analytics actor 的 canonical identity（归一身份）。
 *
 * <p>原始事实不改写；漏斗、留存和活跃用户报表通过本服务使用同一套一跳 alias 规则。</p>
 */
@Service
public class ActorIdentityResolver {

    private static final int QUERY_CHUNK_SIZE = 500;

    public Map<String, String> resolveCanonicalActors(
            JdbcTemplate jdbcTemplate,
            String linkTable,
            String projectId,
            Collection<String> actorIds
    ) {
        NormalizedActors actors = normalizeActors(actorIds);
        Map<String, String> aliasesBySource = new HashMap<>();

        List<String> values = List.copyOf(actors.uuidValues());
        for (int start = 0; start < values.size(); start += QUERY_CHUNK_SIZE) {
            List<String> chunk = values.subList(start, Math.min(start + QUERY_CHUNK_SIZE, values.size()));
            String placeholders = String.join(",", chunk.stream().map(ignored -> "?::uuid").toList());
            String sql = String.format(
                    "SELECT source_actor_id::text, canonical_actor_id::text FROM %s "
                            + "WHERE project_id = ? AND source_actor_id IN (%s)",
                    linkTable,
                    placeholders
            );
            Object[] args = new Object[chunk.size() + 1];
            args[0] = projectId;
            for (int index = 0; index < chunk.size(); index++) {
                args[index + 1] = chunk.get(index);
            }
            jdbcTemplate.query(
                    sql,
                    (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> aliasesBySource.put(
                            resultSet.getString(1),
                            resultSet.getString(2)
                    ),
                    args
            );
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        actors.rawToNormalized().forEach((raw, normalized) ->
                resolved.put(raw, aliasesBySource.getOrDefault(normalized, normalized))
        );
        return Map.copyOf(resolved);
    }

    /**
     * 将管理员输入的 raw 或 canonical actor 展开为同一归一身份下的全部直接成员。
     *
     * <p>当前 identity link 契约只允许一跳关联，因此这里先将 source 解析为 canonical，
     * 再返回 canonical 与它的全部 source。事件列表和锚点旅程必须复用这一边界，
     * 避免各自实现后产生不同的身份查询口径。</p>
     */
    public List<String> resolveActorMembers(
            JdbcTemplate jdbcTemplate,
            String linkTable,
            String projectId,
            String actorId
    ) {
        String normalized = UUID.fromString(actorId).toString();
        List<String> canonicalMatches = jdbcTemplate.queryForList(
                String.format(
                        "SELECT canonical_actor_id::text FROM %s WHERE project_id = ? "
                                + "AND source_actor_id = ?::uuid",
                        linkTable
                ),
                String.class,
                projectId,
                normalized
        );
        String canonicalActor = canonicalMatches.isEmpty() ? normalized : canonicalMatches.getFirst();
        return jdbcTemplate.queryForList(
                String.format(
                        "SELECT source_actor_id::text FROM %s WHERE project_id = ? "
                                + "AND canonical_actor_id = ?::uuid UNION SELECT ?",
                        linkTable
                ),
                String.class,
                projectId,
                canonicalActor,
                canonicalActor
        );
    }

    /**
     * 判断两个 actor 表达是否指向同一身份；UUID 大小写差异不构成 alias 关联。
     */
    public boolean representsSameActor(String first, String second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        if (first.equals(second)) {
            return true;
        }
        try {
            return UUID.fromString(first).equals(UUID.fromString(second));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static NormalizedActors normalizeActors(Collection<String> actorIds) {
        Map<String, String> rawToNormalized = new LinkedHashMap<>();
        Set<String> uuidValues = new LinkedHashSet<>();
        for (String actorId : actorIds) {
            if (actorId == null || actorId.isBlank()) {
                continue;
            }
            try {
                String normalized = UUID.fromString(actorId).toString();
                rawToNormalized.put(actorId, normalized);
                uuidValues.add(normalized);
            } catch (IllegalArgumentException ignored) {
                // 历史非 UUID user_id 按原值参与报表，但不会进入新版 actor alias 查询。
                rawToNormalized.put(actorId, actorId);
            }
        }
        return new NormalizedActors(rawToNormalized, uuidValues);
    }

    private record NormalizedActors(Map<String, String> rawToNormalized, Set<String> uuidValues) {
    }
}
