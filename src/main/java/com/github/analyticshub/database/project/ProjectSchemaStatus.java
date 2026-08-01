package com.github.analyticshub.database.project;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/**
 * Read-only status of one prefix-scoped project schema.
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
