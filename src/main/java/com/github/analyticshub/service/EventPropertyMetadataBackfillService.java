package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 为 V8 之前的事件小批次补齐属性查询元数据。
 *
 * <p>回填不属于项目启动迁移：每批在独立项目事务中提交，失败后可从剩余空值继续，
 * 避免对历史事件表执行一次性全表更新。多实例通过 {@code SKIP LOCKED} 分领记录。</p>
 */
@Service
public class EventPropertyMetadataBackfillService {

    static final int MAX_BATCH_SIZE = 100;

    private final MultiDataSourceManager dataSourceManager;
    private final ProjectTransactionExecutor projectTransactions;
    private final EventMetadataSchemaSupport metadataSchemaSupport;

    public EventPropertyMetadataBackfillService(
            MultiDataSourceManager dataSourceManager,
            ProjectTransactionExecutor projectTransactions,
            EventMetadataSchemaSupport metadataSchemaSupport
    ) {
        this.dataSourceManager = dataSourceManager;
        this.projectTransactions = projectTransactions;
        this.metadataSchemaSupport = metadataSchemaSupport;
    }

    /**
     * 补齐下一批旧事件。返回实际更新条数；零表示本次没有可领取记录。
     */
    public int backfillNextBatch(String projectId, int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, MAX_BATCH_SIZE));
        DataSource dataSource = dataSourceManager.getDataSource(projectId);
        String eventsTable = dataSourceManager.getTableName(projectId, "events");

        return projectTransactions.execute(dataSource, jdbcTemplate -> {
            if (!metadataSchemaSupport.supportsMetadataColumns(jdbcTemplate, projectId, eventsTable)) {
                return 0;
            }
            return jdbcTemplate.update("""
                WITH batch AS (
                    SELECT id
                    FROM %s
                    WHERE project_id = ?
                      AND properties_size_bytes IS NULL
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE %s AS event
                SET properties_size_bytes = COALESCE(octet_length(event.properties::text), 0),
                    identity_scope = CASE
                        WHEN jsonb_typeof(event.properties -> 'identity_scope') = 'string'
                         AND length(event.properties ->> 'identity_scope') <= 64
                        THEN event.properties ->> 'identity_scope'
                        ELSE NULL
                    END
                FROM batch
                WHERE event.id = batch.id
                  AND event.properties_size_bytes IS NULL
                """.formatted(eventsTable, eventsTable), projectId, batchSize);
        });
    }
}
