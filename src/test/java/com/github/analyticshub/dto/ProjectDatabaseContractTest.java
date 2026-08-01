package com.github.analyticshub.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDatabaseContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void healthUsesStableDatabaseTableKeysAndExposesMigrationState() throws Exception {
        ProjectHealthResult result = new ProjectHealthResult(
                true,
                Map.of("traffic_metrics", true, "idempotency_keys", true),
                true,
                true,
                true,
                "2",
                0,
                "analytics_flyway_history",
                null,
                null
        );

        JsonNode json = objectMapper.valueToTree(result);

        assertThat(json.path("tables").path("traffic_metrics").asBoolean()).isTrue();
        assertThat(json.path("tables").path("idempotency_keys").asBoolean()).isTrue();
        assertThat(json.path("schemaCurrent").asBoolean()).isTrue();
        assertThat(json.path("migrationHistoryValid").asBoolean()).isTrue();
        assertThat(json.path("schemaVersion").asString()).isEqualTo("2");
        assertThat(json.path("pendingMigrations").asInt()).isZero();
        assertThat(json.has("errorCode")).isTrue();
    }

    @Test
    void initResultKeepsOriginalFieldsAndAddsMigrationFacts() {
        ProjectInitResult result = new ProjectInitResult(
                "项目数据库迁移成功",
                List.of("analytics_events", "analytics_idempotency_keys"),
                "2",
                1,
                "analytics_flyway_history",
                true
        );

        JsonNode json = objectMapper.valueToTree(result);

        assertThat(json.path("message").asString()).isEqualTo("项目数据库迁移成功");
        assertThat(json.path("tables")).hasSize(2);
        assertThat(json.path("schemaVersion").asString()).isEqualTo("2");
        assertThat(json.path("migrationsExecuted").asInt()).isEqualTo(1);
        assertThat(json.path("legacyBaselineApplied").asBoolean()).isTrue();
    }
}
