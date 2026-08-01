package com.github.analyticshub.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationProfileConfigurationTest {

    private static final String PROFILE_PROPERTY = "spring.config.activate.on-profile";

    @Test
    void baseConfigurationIsSafeWithoutImplicitDevProfile() throws IOException {
        List<PropertySource<?>> documents = loadApplicationYaml();
        PropertySource<?> base = documentForProfile(documents, null);

        assertNull(base.getProperty("spring.profiles.active"));
        assertEquals(Boolean.TRUE, base.getProperty("spring.flyway.clean-disabled"));
        assertEquals(
                "org.apache.ibatis.logging.slf4j.Slf4jImpl",
                base.getProperty("mybatis-plus.configuration.log-impl")
        );
        assertEquals("INFO", base.getProperty("logging.level.com.github.analyticshub"));
        assertEquals("never", base.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", base.getProperty("server.error.include-message"));
        assertEquals("never", base.getProperty("server.error.include-binding-errors"));
        assertTrue(String.valueOf(base.getProperty(
                        "app.security.allow-insecure-device-reregistration"))
                .endsWith(":false}"));
    }

    @Test
    void smtpTimeoutDefaultsAreBoundedWellBelowOutboxClaimReclaim() throws IOException {
        PropertySource<?> base = documentForProfile(loadApplicationYaml(), null);
        long claimTimeoutMs = placeholderDefault(
                base,
                "app.work-order.outbox.claim-timeout-seconds"
        ) * 1_000;

        assertTrue(String.valueOf(base.getProperty(
                        "spring.mail.properties.mail.smtp.connectiontimeout"))
                .contains("MAIL_CONNECTION_TIMEOUT_MS"));
        assertTrue(String.valueOf(base.getProperty(
                        "spring.mail.properties.mail.smtp.timeout"))
                .contains("MAIL_READ_TIMEOUT_MS"));
        assertTrue(String.valueOf(base.getProperty(
                        "spring.mail.properties.mail.smtp.writetimeout"))
                .contains("MAIL_WRITE_TIMEOUT_MS"));

        for (String property : List.of(
                "spring.mail.properties.mail.smtp.connectiontimeout",
                "spring.mail.properties.mail.smtp.timeout",
                "spring.mail.properties.mail.smtp.writetimeout"
        )) {
            long timeoutMs = placeholderDefault(base, property);
            assertTrue(timeoutMs > 0);
            assertTrue(timeoutMs <= claimTimeoutMs / 10);
        }
    }

    @Test
    void onlyDevProfileEnablesFlywayClean() throws IOException {
        List<PropertySource<?>> documents = loadApplicationYaml();
        PropertySource<?> dev = documentForProfile(documents, "dev");
        PropertySource<?> prod = documentForProfile(documents, "prod");

        assertEquals(Boolean.FALSE, dev.getProperty("spring.flyway.clean-disabled"));
        assertEquals("DEBUG", dev.getProperty("logging.level.com.github.analyticshub"));
        assertNull(prod.getProperty("spring.flyway.clean-disabled"));
        assertEquals("INFO", prod.getProperty("logging.level.com.github.analyticshub"));
        assertFalse(documents.stream()
                .filter(document -> document != dev)
                .anyMatch(document -> Boolean.FALSE.equals(
                        document.getProperty("spring.flyway.clean-disabled")
                )));
    }

    @Test
    void corsAllowsTheAdminOtpHeaderRequiredByTwoFactorAuthentication() throws IOException {
        CorsConfiguration cors = CorsConfig.createConfiguration(new CorsProperties());
        PropertySource<?> base = documentForProfile(loadApplicationYaml(), null);

        assertNotNull(cors.getAllowedHeaders());
        assertTrue(cors.getAllowedHeaders().contains("X-Admin-OTP"));
        assertTrue(String.valueOf(base.getProperty("app.cors.allowed-headers"))
                .contains("X-Admin-OTP"));
        assertNotNull(cors.getAllowedMethods());
        assertTrue(cors.getAllowedMethods().contains("PATCH"));
        assertTrue(String.valueOf(base.getProperty("app.cors.allowed-methods"))
                .contains("PATCH"));
        assertEquals(Boolean.FALSE, cors.getAllowCredentials());
        assertTrue(String.valueOf(base.getProperty("app.cors.allowed-origins"))
                .contains("APP_CORS_ALLOWED_ORIGINS"));
    }

    private static List<PropertySource<?>> loadApplicationYaml() throws IOException {
        return new YamlPropertySourceLoader().load(
                "application.yml",
                new ClassPathResource("application.yml")
        );
    }

    private static PropertySource<?> documentForProfile(
            List<PropertySource<?>> documents,
            String profile
    ) {
        return documents.stream()
                .filter(document -> {
                    Object configuredProfile = document.getProperty(PROFILE_PROPERTY);
                    return profile == null ? configuredProfile == null : profile.equals(configuredProfile);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing application.yml document for profile: " + profile));
    }

    private static long placeholderDefault(PropertySource<?> source, String propertyName) {
        Object configured = source.getProperty(propertyName);
        if (configured instanceof Number number) {
            return number.longValue();
        }
        String value = String.valueOf(configured);
        int separator = value.lastIndexOf(':');
        if (!value.startsWith("${") || separator < 0 || !value.endsWith("}")) {
            throw new AssertionError("Expected a placeholder with a numeric default: " + propertyName);
        }
        return Long.parseLong(value.substring(separator + 1, value.length() - 1));
    }
}
