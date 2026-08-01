package com.github.analyticshub;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SystemDatabaseStartupFailureIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("startup_failure_test")
            .withUsername("startup_test")
            .withPassword("startup_test_password");

    @Test
    void refusesToStartWhenMigrationsAreDisabledAndCoreSchemaIsMissing() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(AnalyticshubJavabackApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=analytics,public",
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.flyway.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--spring.devtools.restart.enabled=false",
                        "--logging.level.root=ERROR"
                ))
                .hasRootCauseInstanceOf(PSQLException.class)
                .hasStackTraceContaining("analytics_projects");
    }
}
