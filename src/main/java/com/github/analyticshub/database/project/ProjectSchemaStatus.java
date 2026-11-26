package com.github.analyticshub.database.project;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/**
 * 单个项目表前缀的迁移状态；读取状态不会改动项目库。
 */
public record ProjectSchemaStatus(
        String schema,
        String tablePrefix,
        String historyTable,
        boolean historyTableExists,
        boolean migrationHistoryValid,
        String currentVersion,
        int pendingMigrations,
        Map<String, Boolean> tables,
        boolean allTablesExist,
        boolean current
) {
    public ProjectSchemaStatus {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }
}
