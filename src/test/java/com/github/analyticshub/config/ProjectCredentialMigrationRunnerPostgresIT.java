package com.github.analyticshub.config;

import com.github.analyticshub.security.ProjectCredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProjectCredentialMigrationRunnerPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("credential_migration_test")
            .withUsername("credential_test")
            .withPassword("credential_test_password");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactions;
    private ProjectCredentialCipher cipher;
    private ProjectCredentialMigrationRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbcTemplate.execute("DROP TABLE IF EXISTS analytics_projects");
        jdbcTemplate.execute("CREATE TABLE analytics_projects (" +
                "id BIGSERIAL PRIMARY KEY, project_id VARCHAR(50) NOT NULL UNIQUE, " +
                "db_password_encrypted TEXT, updated_at TIMESTAMPTZ DEFAULT NOW())");

        ProjectCredentialEncryptionProperties properties = new ProjectCredentialEncryptionProperties();
        properties.setEncryptionKey(base64Key());
        cipher = new ProjectCredentialCipher(properties);
        runner = new ProjectCredentialMigrationRunner(jdbcTemplate, cipher, new MockEnvironment());
    }

    @Test
    void migratesLegacyValuesToAuthenticatedEncryption() {
        String legacy = legacy("database-password");
        insert("project_one", legacy);

        transactions.executeWithoutResult(status -> runner.run(new DefaultApplicationArguments()));

        String migrated = stored("project_one");
        assertThat(migrated).startsWith("enc:v1:").isNotEqualTo(legacy);
        assertThat(cipher.decrypt("project_one", migrated)).isEqualTo("database-password");
    }

    @Test
    void malformedLegacyValueRollsBackTheWholeCredentialMigration() {
        String firstLegacy = legacy("first-password");
        insert("project_one", firstLegacy);
        insert("project_two", "not-valid-base64!");

        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> runner.run(new DefaultApplicationArguments())
        )).isInstanceOf(IllegalStateException.class);

        assertThat(stored("project_one")).isEqualTo(firstLegacy);
        assertThat(stored("project_two")).isEqualTo("not-valid-base64!");
    }

    private void insert(String projectId, String password) {
        jdbcTemplate.update(
                "INSERT INTO analytics_projects (project_id, db_password_encrypted) VALUES (?, ?)",
                projectId,
                password
        );
    }

    private String stored(String projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT db_password_encrypted FROM analytics_projects WHERE project_id = ?",
                String.class,
                projectId
        );
    }

    private static String legacy(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Key() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
