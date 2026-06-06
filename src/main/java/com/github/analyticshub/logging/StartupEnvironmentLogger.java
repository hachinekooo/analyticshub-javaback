package com.github.analyticshub.logging;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * 启动时打印关键环境变量。
 * 仅 dev 环境允许输出原值，test/prod 等非 dev 环境一律对敏感配置脱敏。
 */
@Component
public class StartupEnvironmentLogger {

    private static final System.Logger log = System.getLogger(StartupEnvironmentLogger.class.getName());

    private static final String[] SENSITIVE_KEYS = {
            "PASSWORD",
            "PASS",
            "SECRET",
            "TOKEN",
            "API_KEY",
            "ACCESS_KEY",
            "SECRET_KEY",
            "PRIVATE_KEY",
            "JWT",
            "SIGNATURE",
            "COOKIE",
            "DATABASE_URL",
            "JDBC",
            "DATASOURCE",
            "USER",
            "USERNAME",
            "EMAIL",
            "ADDR",
            "HOST"
    };

    private static final String[] RELEVANT_ENV_KEYS = {
            "SPRING_PROFILES_ACTIVE",
            "SERVER_PORT",
            "DB_HOST",
            "DB_PORT",
            "DB_NAME",
            "DB_USER",
            "DB_PASSWORD",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "ADMIN_TOKEN",
            "LOG_PATH",
            "LOG_FILE",
            "LOG_MAX_FILE_SIZE",
            "LOG_MAX_HISTORY",
            "LOG_TOTAL_SIZE_CAP",
            "MAIL_ENABLED",
            "MAIL_HOST",
            "MAIL_PORT",
            "MAIL_USERNAME",
            "MAIL_PASSWORD",
            "ALERT_EMAIL",
            "APP_SECURITY_2FA_ENABLED",
            "APP_SECURITY_2FA_SECRET"
    };

    private final Environment environment;

    public StartupEnvironmentLogger(Environment environment) {
        this.environment = environment;
    }

    public void logStartupEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        String profiles = activeProfiles.length == 0 ? "default" : String.join(",", activeProfiles);
        boolean isDev = environment.acceptsProfiles(Profiles.of("dev"));

        log.log(System.Logger.Level.INFO, "Active profiles: {0}", profiles);

        Map<String, String> envVars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int configured = 0;
        for (String key : RELEVANT_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                configured++;
                envVars.put(key, value);
            } else {
                envVars.put(key, null);
            }
        }

        log.log(System.Logger.Level.INFO,
                "Loaded relevant environment variables (configured {0} / total {1}):",
                configured,
                RELEVANT_ENV_KEYS.length);

        for (String key : RELEVANT_ENV_KEYS) {
            String value = envVars.get(key);
            if (value == null) {
                log.log(System.Logger.Level.INFO, "{0}=<not configured>", key);
                continue;
            }
            if (isDev) {
                log.log(System.Logger.Level.INFO, "{0}={1}", key, value);
            } else {
                log.log(System.Logger.Level.INFO, "{0}={1}", key, maskIfSensitive(key, value));
            }
        }
    }

    private static String maskIfSensitive(String key, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        
        if (!isSensitiveKey(key)) {
            return value;
        }
        return mask(value);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String upper = key.toUpperCase();
        for (String token : SENSITIVE_KEYS) {
            if (upper.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "****";
        }
        return "<redacted> (len=" + value.length() + ")";
    }
}
