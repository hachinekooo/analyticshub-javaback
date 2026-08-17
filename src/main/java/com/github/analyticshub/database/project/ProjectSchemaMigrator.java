package com.github.analyticshub.database.project;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owns the Flyway lifecycle of a connected project's database schema.
 *
 * <p>System database migrations and project database migrations are separate
 * migration streams. A project history table is also scoped by table prefix,
 * allowing multiple projects to share a database/schema without sharing
 * migration state.</p>
 */
@Component
public class ProjectSchemaMigrator {

    static final String MIGRATION_LOCATION = "classpath:db/project-migration";
    static final String HISTORY_TABLE_SUFFIX = "flyway_history";

    private static final int MAX_SCHEMA_NAME_LENGTH = 63;
    private static final int MAX_TABLE_PREFIX_LENGTH = 40;
    private static final int MAX_IDENTIFIER_LENGTH = 63;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private static final List<String> LEGACY_V1_TABLES = List.of(
            "devices",
            "events",
            "sessions",
            "traffic_metrics",
            "counters",
            "privacy_requests"
    );

    private static final List<String> MANAGED_TABLES = List.of(
            "devices",
            "events",
            "sessions",
            "traffic_metrics",
            "counters",
            "privacy_requests",
            "idempotency_keys",
            "actor_identity_links",
            "actor_suppressions",
            "work_order_activities",
            "work_order_outbox"
    );

    /**
     * Migrates a project schema to the latest bundled version.
     *
     * <p>An untracked but complete 1.0.0 schema is baselined at V1 so its
     * data is preserved and only later migrations run. A partially-created
     * legacy schema is rejected because guessing which DDL succeeded can hide
     * production drift.</p>
     */
    public ProjectSchemaMigrationResult migrate(DataSource dataSource, String schema, String tablePrefix) {
        Inputs inputs = validateInputs(dataSource, schema, tablePrefix);
        String lockName = "analyticshub:project-schema:" + inputs.schema() + ":" + inputs.tablePrefix();
        try (Connection connection = dataSource.getConnection()) {
            acquireMigrationLock(connection, lockName);
            try {
                DataSource lockedDataSource = new SingleConnectionDataSource(connection, true);
                return migrateLocked(lockedDataSource, inputs);
            } finally {
                releaseMigrationLock(connection, lockName);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to lock project schema migration", exception);
        }
    }

    private ProjectSchemaMigrationResult migrateLocked(DataSource dataSource, Inputs inputs) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        LegacyState legacyState = classifyUntrackedSchema(jdbcTemplate, inputs);

        FluentConfiguration configuration = baseConfiguration(dataSource, inputs)
                .createSchemas(true);

        boolean legacyBaselineApplied = false;
        if (!legacyState.historyTableExists()) {
            configuration.baselineOnMigrate(true);
            if (legacyState.completeLegacySchema()) {
                configuration
                        .baselineVersion("1")
                        .baselineDescription("AnalyticsHub 1.0.0 project schema");
                legacyBaselineApplied = true;
            } else {
                // The target schema may contain another project's prefix. A V0
                // baseline keeps that schema non-destructive while still running V1.
                configuration
                        .baselineVersion("0")
                        .baselineDescription("Empty prefix baseline");
            }
        }

        MigrateResult migrateResult = configuration.load().migrate();
        ProjectSchemaStatus status = inspect(dataSource, inputs.schema(), inputs.tablePrefix());
        if (!status.current()) {
            throw new IllegalStateException("Project schema migration completed without a healthy latest schema");
        }

        return new ProjectSchemaMigrationResult(
                inputs.schema(),
                inputs.tablePrefix(),
                inputs.historyTable(),
                migrateResult.initialSchemaVersion,
                status.currentVersion(),
                migrateResult.migrationsExecuted,
                legacyBaselineApplied,
                physicalTableNames(inputs.tablePrefix())
        );
    }

    private static void acquireMigrationLock(Connection connection, String lockName) throws SQLException {
        executeAdvisoryLock(connection, "SELECT pg_advisory_lock(hashtextextended(?, 0))", lockName);
    }

    private static void releaseMigrationLock(Connection connection, String lockName) throws SQLException {
        executeAdvisoryLock(connection, "SELECT pg_advisory_unlock(hashtextextended(?, 0))", lockName);
    }

    private static void executeAdvisoryLock(Connection connection, String sql, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lockName);
            statement.execute();
        }
    }

    /**
     * Inspects migration and table status without modifying the target schema.
     */
    public ProjectSchemaStatus inspect(DataSource dataSource, String schema, String tablePrefix) {
        Inputs inputs = validateInputs(dataSource, schema, tablePrefix);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        boolean schemaExists = schemaExists(jdbcTemplate, inputs.schema());
        Map<String, Boolean> tables = tableStatus(jdbcTemplate, inputs, MANAGED_TABLES);
        boolean historyTableExists = schemaExists
                && tableExists(jdbcTemplate, inputs.schema(), inputs.historyTable());

        Flyway flyway = baseConfiguration(dataSource, inputs)
                .createSchemas(false)
                .load();
        MigrationInfoService migrationInfo = flyway.info();
        ValidateResult validation = flyway.validateWithResult();
        MigrationInfo current = migrationInfo.current();
        String currentVersion = current == null || current.getVersion() == null
                ? null
                : current.getVersion().getVersion();
        int pendingMigrations = migrationInfo.pending().length;
        boolean migrationHistoryValid = validation.validationSuccessful;
        boolean allTablesExist = tables.values().stream().allMatch(Boolean::booleanValue);
        boolean isCurrent = historyTableExists
                && migrationHistoryValid
                && pendingMigrations == 0
                && allTablesExist;

        return new ProjectSchemaStatus(
                inputs.schema(),
                inputs.tablePrefix(),
                inputs.historyTable(),
                historyTableExists,
                migrationHistoryValid,
                currentVersion,
                pendingMigrations,
                tables,
                allTablesExist,
                isCurrent
        );
    }

    private static FluentConfiguration baseConfiguration(DataSource dataSource, Inputs inputs) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .schemas(inputs.schema())
                .defaultSchema(inputs.schema())
                .table(inputs.historyTable())
                .placeholders(Map.of(
                        "schema", inputs.schema(),
                        "tablePrefix", inputs.tablePrefix()
                ))
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true);
    }

    private static LegacyState classifyUntrackedSchema(JdbcTemplate jdbcTemplate, Inputs inputs) {
        boolean historyExists = tableExists(jdbcTemplate, inputs.schema(), inputs.historyTable());
        if (historyExists) {
            return new LegacyState(true, false);
        }

        Map<String, Boolean> legacyTables = tableStatus(jdbcTemplate, inputs, LEGACY_V1_TABLES);
        List<String> incompatibleRelations = LEGACY_V1_TABLES.stream()
                .filter(baseTable -> relationExists(
                        jdbcTemplate,
                        inputs.schema(),
                        inputs.tablePrefix() + baseTable
                ))
                .filter(baseTable -> !legacyTables.get(baseTable))
                .toList();
        if (!incompatibleRelations.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to migrate AnalyticsHub 1.0.0 project schema; expected ordinary tables but found "
                            + "views or other relation types: " + String.join(", ", incompatibleRelations));
        }

        long existingTableCount = legacyTables.values().stream().filter(Boolean::booleanValue).count();
        if (existingTableCount > 0 && existingTableCount < LEGACY_V1_TABLES.size()) {
            List<String> missingTables = legacyTables.entrySet().stream()
                    .filter(entry -> !entry.getValue())
                    .map(Map.Entry::getKey)
                    .toList();
            throw new IllegalStateException(
                    "Refusing to migrate partial AnalyticsHub 1.0.0 project schema; missing tables: "
                            + String.join(", ", missingTables));
        }
        boolean completeLegacySchema = existingTableCount == LEGACY_V1_TABLES.size();
        if (completeLegacySchema) {
            List<String> fingerprintProblems = LegacyProjectSchemaV1Verifier.verify(
                    jdbcTemplate,
                    inputs.schema(),
                    inputs.tablePrefix()
            );
            if (!fingerprintProblems.isEmpty()) {
                throw new IllegalStateException(
                        "Refusing to baseline incompatible AnalyticsHub 1.0.0 project schema: "
                                + String.join("; ", fingerprintProblems));
            }
        }
        return new LegacyState(false, completeLegacySchema);
    }

    private static Map<String, Boolean> tableStatus(
            JdbcTemplate jdbcTemplate,
            Inputs inputs,
            List<String> baseTableNames
    ) {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (String baseTableName : baseTableNames) {
            status.put(baseTableName,
                    tableExists(jdbcTemplate, inputs.schema(), inputs.tablePrefix() + baseTableName));
        }
        return status;
    }

    private static boolean schemaExists(JdbcTemplate jdbcTemplate, String schema) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                Boolean.class,
                schema
        );
        return Boolean.TRUE.equals(exists);
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String schema, String table) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_class cls
                    JOIN pg_namespace ns ON ns.oid = cls.relnamespace
                    WHERE ns.nspname = ? AND cls.relname = ? AND cls.relkind IN ('r', 'p')
                )
                """,
                Boolean.class,
                schema,
                table
        );
        return Boolean.TRUE.equals(exists);
    }

    private static boolean relationExists(JdbcTemplate jdbcTemplate, String schema, String relation) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_class cls
                    JOIN pg_namespace ns ON ns.oid = cls.relnamespace
                    WHERE ns.nspname = ? AND cls.relname = ?
                )
                """,
                Boolean.class,
                schema,
                relation
        );
        return Boolean.TRUE.equals(exists);
    }

    private static List<String> physicalTableNames(String tablePrefix) {
        return MANAGED_TABLES.stream().map(tablePrefix::concat).toList();
    }

    private static Inputs validateInputs(DataSource dataSource, String schema, String tablePrefix) {
        Objects.requireNonNull(dataSource, "dataSource");
        validateIdentifier(schema, "schema", MAX_SCHEMA_NAME_LENGTH);
        validateTablePrefix(tablePrefix);

        String historyTable = tablePrefix + HISTORY_TABLE_SUFFIX;
        validateIdentifier(historyTable, "history table", MAX_IDENTIFIER_LENGTH);
        for (String baseTable : MANAGED_TABLES) {
            validateIdentifier(tablePrefix + baseTable, "project table", MAX_IDENTIFIER_LENGTH);
        }
        return new Inputs(schema, tablePrefix, historyTable);
    }

    private static void validateTablePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.length() > MAX_TABLE_PREFIX_LENGTH) {
            throw new IllegalArgumentException("Invalid table prefix");
        }
        if (!tablePrefix.isEmpty() && !IDENTIFIER_PATTERN.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("Invalid table prefix");
        }
    }

    private static void validateIdentifier(String value, String type, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + type);
        }
    }

    private record Inputs(String schema, String tablePrefix, String historyTable) {}

    private record LegacyState(boolean historyTableExists, boolean completeLegacySchema) {}
}
