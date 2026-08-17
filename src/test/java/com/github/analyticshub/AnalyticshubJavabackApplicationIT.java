package com.github.analyticshub;

import com.github.analyticshub.controller.HealthController;
import com.github.analyticshub.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1"
)
class AnalyticshubJavabackApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("analytics_test")
            .withUsername("analytic_test")
            .withPassword("analytic_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=analytics,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "analytics");
        registry.add("spring.flyway.schemas", () -> "analytics");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("app.security.actor-link.enabled", () -> true);
        registry.add("app.security.actor-link.require-loopback", () -> true);
        registry.add("app.security.actor-link.clients[0].service-id", () -> "backend-test");
        registry.add("app.security.actor-link.clients[0].project-id", () -> "project-test");
        registry.add("app.security.actor-link.clients[0].secret", () -> "actor-link-test-secret-with-at-least-32-characters");
        registry.add("app.security.actor-link.clients[1].service-id", () -> "backend-prod");
        registry.add("app.security.actor-link.clients[1].project-id", () -> "project-prod");
        registry.add("app.security.actor-link.clients[1].secret", () -> "actor-link-prod-secret-with-at-least-32-characters");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HealthController healthController;

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int serverPort;

    @Test
    void startsOnlyAfterSystemDatabaseMigrationsSucceed() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        Boolean projectsTableExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('analytics.analytics_projects') IS NOT NULL",
                Boolean.class
        );
        String latestMigration = jdbcTemplate.queryForObject(
                "SELECT version FROM analytics.flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class
        );

        assertThat(currentSchema).isEqualTo("analytics");
        assertThat(projectsTableExists).isTrue();
        assertThat(latestMigration).isEqualTo("5");
        String analysisTemplate = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns " +
                        "WHERE table_schema = 'analytics' AND table_name = 'analytics_projects' " +
                        "AND column_name = 'analysis_template'",
                String.class
        );
        assertThat(analysisTemplate).contains("app");
    }

    @Test
    void upgradesExistingV4ProjectsToTheAppTemplateWithoutDataLoss() {
        String schema = "analytics_upgrade_v4";
        Flyway v4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("4"))
                .load();
        v4.migrate();

        JdbcTemplate upgradeDatabase = new JdbcTemplate(v4.getConfiguration().getDataSource());
        upgradeDatabase.update("""
                INSERT INTO analytics_upgrade_v4.analytics_projects
                    (project_id, project_name, db_host, db_name, db_user)
                VALUES (?, ?, ?, ?, ?)
                """, "existing_app", "Existing App", "localhost", "existing_app", "existing_app");
        upgradeDatabase.update("""
                INSERT INTO analytics_upgrade_v4.analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition, is_default)
                VALUES (?, 'operations', '{"en":"Operations"}'::jsonb, 1,
                    '{"schemaVersion":1,"widgets":[]}'::jsonb, TRUE)
                """, "existing_app");
        upgradeDatabase.update("""
                INSERT INTO analytics_upgrade_v4.analytics_dashboards
                    (project_id, dashboard_key, display_name, schema_version, definition, is_default)
                VALUES (?, 'technical', '{"en":"Details"}'::jsonb, 1,
                    '{"schemaVersion":1,"widgets":[
                        {"id":"events","type":"core.events","layout":{"x":0,"y":0,"w":6,"h":8}},
                        {"id":"traffic","type":"core.traffic","layout":{"x":6,"y":0,"w":6,"h":8}}
                    ]}'::jsonb, FALSE)
                """, "existing_app");

        Flyway.configure()
                .dataSource(v4.getConfiguration().getDataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(upgradeDatabase.queryForObject(
                "SELECT analysis_template FROM analytics_upgrade_v4.analytics_projects WHERE project_id = ?",
                String.class,
                "existing_app"
        )).isEqualTo("app");
        assertThat(upgradeDatabase.queryForObject(
                "SELECT project_name FROM analytics_upgrade_v4.analytics_projects WHERE project_id = ?",
                String.class,
                "existing_app"
        )).isEqualTo("Existing App");
        assertThat(upgradeDatabase.queryForObject(
                "SELECT dashboard_key FROM analytics_upgrade_v4.analytics_dashboards " +
                        "WHERE project_id = ? AND display_name ->> 'en' = 'Operations'",
                String.class,
                "existing_app"
        )).isEqualTo("overview");
        assertThat(upgradeDatabase.queryForObject(
                "SELECT COUNT(*) FROM analytics_upgrade_v4.analytics_dashboards d " +
                        "CROSS JOIN LATERAL jsonb_array_elements(d.definition -> 'widgets') widget " +
                        "WHERE d.project_id = ? AND d.dashboard_key = 'details' " +
                        "AND widget ->> 'type' = 'core.traffic'",
                Integer.class,
                "existing_app"
        )).isZero();
        assertThat(upgradeDatabase.queryForObject(
                "SELECT definition_origin FROM analytics_upgrade_v4.analytics_semantic_definitions " +
                        "WHERE project_id = ? AND source_kind = 'EVENT_TYPE' AND semantic_key = ?",
                String.class,
                "existing_app",
                "core.action.completed"
        )).isEqualTo("OFFICIAL");
        assertThat(upgradeDatabase.queryForObject(
                "SELECT COUNT(*) FROM analytics_upgrade_v4.flyway_schema_history " +
                        "WHERE success = TRUE AND version = '5'",
                Integer.class
        )).isOne();
    }

    @Test
    void exposesMavenProjectVersionFromBuildMetadata() {
        Map<String, Object> body = healthController.health().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("version")).isEqualTo("1.1.0");
    }

    @Test
    void doesNotCreateAnUnusedGeneratedPasswordUser() {
        assertThat(applicationContext.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    }

    @Test
    void liveServletFirewallUsesTheBoundedApiErrorShape() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        for (String path : java.util.List.of(
                "/api/admin;x=1/projects",
                "/api/admin%3Bx=1/projects"
        )) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + serverPort + path)
                    )
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .startsWith("application/json"));
            assertThat(response.body())
                    .contains("\"code\":\"INVALID_REQUEST_PATH\"")
                    .doesNotContain("RequestRejectedException");
        }

        HttpRequest canonicalAdminRequest = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + "/api/admin/projects")
                )
                .GET()
                .build();
        HttpResponse<String> canonicalAdminResponse = client.send(
                canonicalAdminRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(canonicalAdminResponse.statusCode()).isEqualTo(401);
        assertThat(canonicalAdminResponse.body())
                .contains("\"code\":\"ADMIN_TOKEN_MISSING\"");
    }

    @Test
    void actorLinkSecurityIsEnforcedByTheLiveSpringSecurityFilterChain() throws Exception {
        String actorLinkEndpoint = "/internal/v1/analytics/actor-links";
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"bindingId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"sourceActorId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"canonicalActorId\":\"33333333-3333-4333-8333-333333333333\","
                + "\"linkedAt\":\"2026-01-01T00:00:00Z\"}";

        HttpResponse<String> unsigned = client.send(actorLinkRequestBuilder(actorLinkEndpoint, body).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unsigned.statusCode()).isEqualTo(401);
        assertThat(unsigned.body()).contains("ACTOR_LINK_HEADERS_MISSING");

        HttpRequest ordinaryCollectionCredentials = HttpRequest.newBuilder(actorLinkUri(actorLinkEndpoint))
                .header("Content-Type", "application/json")
                .header("X-Project-ID", "project-test")
                .header("X-API-Key", "ordinary-collection-key")
                .header("X-Device-ID", "22222222-2222-4222-8222-222222222222")
                .header("X-User-ID", "33333333-3333-4333-833333333333")
                .header("X-Timestamp", Long.toString(System.currentTimeMillis()))
                .header("X-Signature", "not-a-service-signature")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> ordinary = client.send(ordinaryCollectionCredentials, HttpResponse.BodyHandlers.ofString());
        assertThat(ordinary.statusCode()).isEqualTo(401);
        assertThat(ordinary.body()).contains("ACTOR_LINK_HEADERS_MISSING");

        String timestamp = Long.toString(System.currentTimeMillis());
        String idempotencyKey = "11111111-1111-4111-8111-111111111111";
        String signatureData = String.join("|", "POST", actorLinkEndpoint, timestamp,
                "backend-test", "project-prod", idempotencyKey, CryptoUtils.sha256Hex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        HttpRequest crossProject = actorLinkRequestBuilder(actorLinkEndpoint, body)
                .header("X-Service-ID", "backend-test")
                .header("X-Project-ID", "project-prod")
                .header("X-Timestamp", timestamp)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-Service-Signature", CryptoUtils.generateSignature(signatureData,
                        "actor-link-test-secret-with-at-least-32-characters"))
                .build();
        HttpResponse<String> crossProjectResponse = client.send(crossProject, HttpResponse.BodyHandlers.ofString());
        assertThat(crossProjectResponse.statusCode()).isEqualTo(403);
        assertThat(crossProjectResponse.body()).contains("ACTOR_LINK_CLIENT_FORBIDDEN");

        HttpResponse<String> unsafe = client.send(actorLinkRequestBuilder(
                        "/internal/v1/analytics/actor-links;unsafe", body).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unsafe.statusCode()).isEqualTo(400);
        assertThat(unsafe.body()).contains("INVALID_REQUEST_PATH");
    }

    private HttpRequest.Builder actorLinkRequestBuilder(String path, String body) {
        return HttpRequest.newBuilder(actorLinkUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI actorLinkUri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }
}
