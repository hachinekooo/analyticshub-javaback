package com.github.analyticshub.database.project;

import java.util.List;

/**
 * 项目库迁移的执行结果，与 HTTP 响应文案无关，可由接口或维护任务复用。
 */
public record ProjectSchemaMigrationResult(
        String schema,
        String tablePrefix,
        String historyTable,
        String initialVersion,
        String currentVersion,
        int migrationsExecuted,
        boolean legacyBaselineApplied,
        List<String> tables
) {
    public ProjectSchemaMigrationResult {
        tables = List.copyOf(tables);
    }
}
