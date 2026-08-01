package com.github.analyticshub.database.project;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable structural fingerprint of the pre-Flyway AnalyticsHub 1.0.0
 * project schema.
 *
 * <p>Flyway baseline records a version but cannot prove what DDL produced an
 * existing untracked schema. Automatic baseline is therefore allowed only
 * when the runtime-relevant columns, keys, checks, and indexes match this
 * frozen fingerprint.</p>
 */
final class LegacyProjectSchemaV1Verifier {

    private static final Map<String, Map<String, ColumnSpec>> EXPECTED_COLUMNS = Map.of(
            "devices", columns(
                    "id:int4:N", "device_id:uuid:N", "api_key:varchar:N:100",
                    "secret_key:varchar:N:100", "device_model:varchar:Y:100",
                    "os_version:varchar:Y:50", "app_version:varchar:Y:50",
                    "project_id:varchar:N:50", "is_banned:bool:Y", "ban_reason:text:Y",
                    "created_at:timestamptz:Y", "last_active_at:timestamptz:Y"
            ),
            "events", columns(
                    "id:int8:N", "event_id:varchar:N:64", "device_id:uuid:N",
                    "user_id:varchar:N:128", "session_id:uuid:Y", "event_type:varchar:N:100",
                    "event_timestamp:int8:N", "properties:jsonb:Y", "project_id:varchar:N:50",
                    "created_at:timestamptz:Y"
            ),
            "sessions", columns(
                    "id:int4:N", "session_id:uuid:N", "device_id:uuid:N",
                    "user_id:varchar:N:128", "session_start_time:timestamptz:N",
                    "session_duration_ms:int8:Y", "device_model:varchar:Y:100",
                    "os_version:varchar:Y:50", "app_version:varchar:Y:50",
                    "build_number:varchar:Y:50", "screen_count:int4:Y", "event_count:int4:Y",
                    "project_id:varchar:N:50", "created_at:timestamptz:Y"
            ),
            "traffic_metrics", columns(
                    "id:int8:N", "metric_id:varchar:N:64", "device_id:uuid:N",
                    "user_id:varchar:N:128", "session_id:uuid:Y", "metric_type:varchar:N:50",
                    "page_path:varchar:Y:255", "referrer:varchar:Y:255",
                    "metric_timestamp:int8:N", "metadata:jsonb:Y", "project_id:varchar:N:50",
                    "created_at:timestamptz:Y"
            ),
            "counters", columns(
                    "id:int8:N", "counter_key:varchar:N:100", "counter_value:int8:N",
                    "display_name:jsonb:Y", "unit:jsonb:Y", "event_trigger:jsonb:Y",
                    "is_public:bool:Y", "description:text:Y", "project_id:varchar:N:50",
                    "created_at:timestamptz:Y", "updated_at:timestamptz:Y"
            ),
            "privacy_requests", columns(
                    "id:int8:N", "request_id:varchar:N:64", "project_id:varchar:N:50",
                    "user_id:varchar:N:64", "device_id:uuid:N", "request_type:varchar:N:16",
                    "processor:varchar:N:32", "source:varchar:N:32", "status:varchar:N:32",
                    "contact_email:varchar:Y:255", "requester_note:text:Y",
                    "operator:varchar:Y:64", "operator_note:text:Y", "result_payload:jsonb:Y",
                    "metadata:jsonb:Y", "requested_at:timestamptz:N", "processed_at:timestamptz:Y",
                    "closed_at:timestamptz:Y", "created_at:timestamptz:N", "updated_at:timestamptz:N"
            )
    );

    private static final Map<String, Set<String>> EXPECTED_KEYS = Map.of(
            "devices", Set.of("p:id", "u:api_key"),
            "events", Set.of("p:id", "u:event_id"),
            "sessions", Set.of("p:id", "u:session_id"),
            "traffic_metrics", Set.of("p:id", "u:metric_id"),
            "counters", Set.of("p:id", "u:project_id,counter_key"),
            "privacy_requests", Set.of("p:id", "u:request_id")
    );

    private static final List<IndexSpec> EXPECTED_INDEXES = List.of(
            index("devices", "idx_devices_device_id", "btree", false, "device_id"),
            index("devices", "idx_devices_api_key", "btree", false, "api_key"),
            index("devices", "idx_devices_project_device", "btree", true, "project_id, device_id"),
            index("events", "idx_events_device_id", "btree", false, "device_id"),
            index("events", "idx_events_user_id", "btree", false, "user_id"),
            index("events", "idx_events_event_type", "btree", false, "event_type"),
            index("events", "idx_events_created_at", "btree", false, "created_at DESC"),
            index("events", "idx_events_project_device", "btree", false, "project_id, device_id"),
            index("events", "idx_events_project_created", "btree", false, "project_id, created_at DESC"),
            index("events", "idx_events_properties", "gin", false, "properties"),
            index("sessions", "idx_sessions_device_id", "btree", false, "device_id"),
            index("sessions", "idx_sessions_user_id", "btree", false, "user_id"),
            index("sessions", "idx_sessions_created_at", "btree", false, "created_at DESC"),
            index("sessions", "idx_sessions_project_device", "btree", false, "project_id, device_id"),
            index("sessions", "idx_sessions_start_time", "btree", false, "session_start_time DESC"),
            index("traffic_metrics", "idx_traffic_device_id", "btree", false, "device_id"),
            index("traffic_metrics", "idx_traffic_user_id", "btree", false, "user_id"),
            index("traffic_metrics", "idx_traffic_type", "btree", false, "metric_type"),
            index("traffic_metrics", "idx_traffic_created_at", "btree", false, "created_at DESC"),
            index("traffic_metrics", "idx_traffic_project_device", "btree", false, "project_id, device_id"),
            index("traffic_metrics", "idx_traffic_project_created", "btree", false, "project_id, created_at DESC"),
            index("traffic_metrics", "idx_traffic_page_path", "btree", false, "page_path"),
            index("traffic_metrics", "idx_traffic_referrer", "btree", false, "referrer"),
            index("traffic_metrics", "idx_traffic_metadata", "gin", false, "metadata"),
            index("counters", "idx_counters_project_updated", "btree", false, "project_id, updated_at DESC"),
            index("privacy_requests", "idx_privacy_project_status_requested", "btree", false,
                    "project_id, status, requested_at DESC"),
            index("privacy_requests", "idx_privacy_project_user_requested", "btree", false,
                    "project_id, user_id, requested_at DESC"),
            index("privacy_requests", "idx_privacy_project_processor_requested", "btree", false,
                    "project_id, processor, requested_at DESC")
    );

    private static final List<CheckSpec> EXPECTED_CHECKS = List.of(
            new CheckSpec("chk_privacy_request_type", List.of("request_type", "EXPORT", "DELETE")),
            new CheckSpec("chk_privacy_processor", List.of("processor", "ANALYTICSHUB", "POSTHOG")),
            new CheckSpec("chk_privacy_status", List.of(
                    "status", "SUBMITTED", "IN_PROGRESS", "COMPLETED", "REJECTED", "CANCELLED"))
    );

    private LegacyProjectSchemaV1Verifier() {
    }

    static List<String> verify(JdbcTemplate jdbcTemplate, String schema, String tablePrefix) {
        List<String> problems = new ArrayList<>();
        EXPECTED_COLUMNS.forEach((baseTable, expected) -> {
            String table = tablePrefix + baseTable;
            Map<String, ColumnSpec> actual = readColumns(jdbcTemplate, schema, table);
            if (!expected.equals(actual)) {
                Set<String> missing = new LinkedHashSet<>(expected.keySet());
                missing.removeAll(actual.keySet());
                Set<String> unexpected = new LinkedHashSet<>(actual.keySet());
                unexpected.removeAll(expected.keySet());
                List<String> mismatched = expected.keySet().stream()
                        .filter(actual::containsKey)
                        .filter(column -> !expected.get(column).equals(actual.get(column)))
                        .toList();
                problems.add(baseTable + " columns mismatch: missing=" + missing
                        + ", unexpected=" + unexpected + ", incompatible=" + mismatched);
            }

            Set<String> actualKeys = readPrimaryAndUniqueKeys(jdbcTemplate, schema, table);
            if (!actualKeys.containsAll(EXPECTED_KEYS.get(baseTable))) {
                Set<String> missingKeys = new LinkedHashSet<>(EXPECTED_KEYS.get(baseTable));
                missingKeys.removeAll(actualKeys);
                problems.add(baseTable + " missing keys: " + missingKeys);
            }
        });

        for (IndexSpec expected : EXPECTED_INDEXES) {
            String table = tablePrefix + expected.baseTable();
            List<IndexRow> rows = jdbcTemplate.query(
                    """
                    SELECT indexname, indexdef
                    FROM pg_indexes
                    WHERE schemaname = ? AND tablename = ? AND indexname = ?
                    """,
                    (rs, rowNum) -> new IndexRow(rs.getString("indexname"), rs.getString("indexdef")),
                    schema,
                    table,
                    expected.name()
            );
            String requiredFragment = (expected.unique() ? "CREATE UNIQUE INDEX" : "CREATE INDEX")
                    + " " + expected.name();
            String usingFragment = "USING " + expected.method() + " (" + expected.expression() + ")";
            if (rows.size() != 1
                    || !rows.getFirst().definition().contains(requiredFragment)
                    || !rows.getFirst().definition().contains(usingFragment)) {
                problems.add(expected.baseTable() + " missing or incompatible index: " + expected.name());
            }
        }

        String privacyTable = tablePrefix + "privacy_requests";
        Map<String, String> checks = jdbcTemplate.query(
                """
                SELECT con.conname, pg_get_constraintdef(con.oid) AS definition
                FROM pg_constraint con
                JOIN pg_class cls ON cls.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = cls.relnamespace
                WHERE ns.nspname = ? AND cls.relname = ? AND con.contype = 'c'
                """,
                rs -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("conname"), rs.getString("definition"));
                    }
                    return result;
                },
                schema,
                privacyTable
        );
        for (CheckSpec expected : EXPECTED_CHECKS) {
            String definition = checks.get(expected.name());
            if (definition == null || expected.requiredTokens().stream().anyMatch(token -> !definition.contains(token))) {
                problems.add("privacy_requests missing or incompatible check: " + expected.name());
            }
        }
        return List.copyOf(problems);
    }

    private static Map<String, ColumnSpec> readColumns(JdbcTemplate jdbcTemplate, String schema, String table) {
        return jdbcTemplate.query(
                """
                SELECT column_name, udt_name, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """,
                rs -> {
                    Map<String, ColumnSpec> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        Number length = (Number) rs.getObject("character_maximum_length");
                        result.put(
                                rs.getString("column_name"),
                                new ColumnSpec(
                                        rs.getString("udt_name"),
                                        "YES".equals(rs.getString("is_nullable")),
                                        length == null ? null : length.intValue()
                                )
                        );
                    }
                    return result;
                },
                schema,
                table
        );
    }

    private static Set<String> readPrimaryAndUniqueKeys(
            JdbcTemplate jdbcTemplate,
            String schema,
            String table
    ) {
        List<String> rows = jdbcTemplate.queryForList(
                """
                SELECT con.contype::text || ':' || string_agg(att.attname, ',' ORDER BY key_column.ordinality)
                FROM pg_constraint con
                JOIN pg_class cls ON cls.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = cls.relnamespace
                JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS key_column(attnum, ordinality) ON TRUE
                JOIN pg_attribute att ON att.attrelid = cls.oid AND att.attnum = key_column.attnum
                WHERE ns.nspname = ? AND cls.relname = ? AND con.contype IN ('p', 'u')
                GROUP BY con.oid, con.contype
                """,
                String.class,
                schema,
                table
        );
        return Set.copyOf(rows);
    }

    private static Map<String, ColumnSpec> columns(String... definitions) {
        Map<String, ColumnSpec> result = new LinkedHashMap<>();
        Arrays.stream(definitions).forEach(definition -> {
            String[] parts = definition.split(":");
            Integer length = parts.length == 4 ? Integer.valueOf(parts[3]) : null;
            result.put(parts[0], new ColumnSpec(parts[1], "Y".equals(parts[2]), length));
        });
        return Map.copyOf(result);
    }

    private static IndexSpec index(
            String baseTable,
            String name,
            String method,
            boolean unique,
            String expression
    ) {
        return new IndexSpec(baseTable, name, method, unique, expression);
    }

    private record ColumnSpec(String udtName, boolean nullable, Integer characterMaximumLength) {
    }

    private record IndexSpec(String baseTable, String name, String method, boolean unique, String expression) {
    }

    private record IndexRow(String name, String definition) {
    }

    private record CheckSpec(String name, List<String> requiredTokens) {
    }
}
