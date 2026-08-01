package com.github.analyticshub.config;

import com.github.analyticshub.security.ProjectCredentialCipher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Migrates the legacy 1.0.0 Base64 project passwords after Flyway has prepared
 * the system database. The whole scan is one transaction and locks affected
 * rows so a malformed value cannot leave a partially migrated credential set.
 */
@Component
@Order(2)
public class ProjectCredentialMigrationRunner implements ApplicationRunner {

    private static final System.Logger log = System.getLogger(ProjectCredentialMigrationRunner.class.getName());

    private final JdbcTemplate jdbcTemplate;
    private final ProjectCredentialCipher credentialCipher;
    private final Environment environment;

    public ProjectCredentialMigrationRunner(
            JdbcTemplate jdbcTemplate,
            ProjectCredentialCipher credentialCipher,
            Environment environment
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialCipher = credentialCipher;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_projects " +
                        "WHERE db_password_encrypted IS NOT NULL AND db_password_encrypted <> ''",
                Long.class
        );
        long configuredCredentialCount = count == null ? 0 : count;
        if (!credentialCipher.isConfigured()) {
            if (production || configuredCredentialCount > 0) {
                throw new IllegalStateException(
                        "PROJECT_CREDENTIAL_ENCRYPTION_KEY is required before AnalyticsHub can use project credentials"
                );
            }
            log.log(System.Logger.Level.WARNING,
                    "Project credential encryption key is not configured; project creation is disabled");
            return;
        }

        List<ProjectCredentialRow> projects = jdbcTemplate.query(
                "SELECT id, project_id, db_password_encrypted FROM analytics_projects " +
                        "WHERE db_password_encrypted IS NOT NULL AND db_password_encrypted <> '' " +
                        "ORDER BY id FOR UPDATE",
                (resultSet, rowNumber) -> new ProjectCredentialRow(
                        resultSet.getLong("id"),
                        resultSet.getString("project_id"),
                        resultSet.getString("db_password_encrypted")
                )
        );

        int migrated = 0;
        for (ProjectCredentialRow project : projects) {
            String storedValue = project.encryptedPassword();
            String plaintext = credentialCipher.decrypt(project.projectId(), storedValue);
            if (!credentialCipher.needsRotation(storedValue)) {
                continue;
            }
            String encrypted = credentialCipher.encrypt(project.projectId(), plaintext);
            int updated = jdbcTemplate.update(
                    "UPDATE analytics_projects SET db_password_encrypted = ?, updated_at = NOW() " +
                            "WHERE id = ? AND db_password_encrypted = ?",
                    encrypted,
                    project.id(),
                    storedValue
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "Project credential changed concurrently during startup migration: projectId="
                                + project.projectId()
                );
            }
            migrated++;
        }
        log.log(System.Logger.Level.INFO,
                "Project credential validation complete: configured={0}, migrated={1}",
                projects.size(), migrated);
    }

    private record ProjectCredentialRow(long id, String projectId, String encryptedPassword) {
    }
}
