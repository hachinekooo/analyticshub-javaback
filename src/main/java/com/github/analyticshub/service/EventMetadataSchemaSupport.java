package com.github.analyticshub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中判断事件表是否已具备 V8 属性元数据列。
 *
 * <p>只永久缓存“已支持”：Project Schema 只向前迁移，因此 V8 一旦就绪不会回退；
 * V7 结果不缓存，管理员完成迁移后下一次请求即可自动切换到新写入路径。</p>
 */
@Component
public class EventMetadataSchemaSupport {

    private final Set<TableKey> readyTables = ConcurrentHashMap.newKeySet();

    public boolean supportsMetadataColumns(
            JdbcTemplate jdbcTemplate,
            String projectId,
            String eventsTable
    ) {
        TableKey cacheKey = new TableKey(projectId, eventsTable, jdbcTemplate.getDataSource());
        if (readyTables.contains(cacheKey)) {
            return true;
        }
        Boolean supported = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) = 2
                FROM pg_attribute
                WHERE attrelid = to_regclass(?)
                  AND attname IN ('properties_size_bytes', 'identity_scope')
                  AND NOT attisdropped
                """,
                Boolean.class,
                eventsTable
        );
        if (Boolean.TRUE.equals(supported)) {
            readyTables.add(cacheKey);
            return true;
        }
        return false;
    }

    private record TableKey(String projectId, String eventsTable, DataSource dataSource) {}
}
