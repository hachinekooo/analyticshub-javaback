package com.github.analyticshub.config;

import com.github.analyticshub.security.ProjectCredentialCipher;
import lombok.RequiredArgsConstructor;
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
 * 系统库迁移后验证现有项目库凭据，并将旧格式或旧密钥密文统一改用当前密钥加密；凭据扫描与更新在同一事务中完成。
 */
@Component
@RequiredArgsConstructor
@Order(2)
public class ProjectCredentialMigrationRunner implements ApplicationRunner {

    private static final System.Logger log = System.getLogger(ProjectCredentialMigrationRunner.class.getName());

    private final JdbcTemplate jdbcTemplate;
    private final ProjectCredentialCipher credentialCipher;
    private final Environment environment;

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
