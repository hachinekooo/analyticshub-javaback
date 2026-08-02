package com.github.analyticshub;

import com.github.analyticshub.controller.HealthController;
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
        assertThat(latestMigration).isEqualTo("7");
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
                "SELECT dashboard_key FROM analytics_upgrade_v4.analytics_dashboards WHERE project_id = ?",
                String.class,
                "existing_app"
        )).isEqualTo("app");
    }

    @Test
    void exposesMavenProjectVersionFromBuildMetadata() {
        Map<String, Object> body = healthController.health().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("version")).isEqualTo("1.0.1");
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
}
