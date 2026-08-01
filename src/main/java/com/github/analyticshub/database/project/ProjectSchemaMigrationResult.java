package com.github.analyticshub.database.project;

import java.util.List;

/**
 * A completed project-schema migration.
 *
 * <p>The result deliberately contains database schema facts rather than an
 * HTTP message so it can also be reused by CLI or scheduled maintenance
 * entrypoints.</p>
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
