package com.github.analyticshub.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathSecurityPolicyTest {

    @Test
    void stripsTheDeclaredContextPathBeforeAuthenticationClassification() {
        RequestPathSecurityPolicy.Inspection inspection =
                RequestPathSecurityPolicy.inspect("/hub/api/admin/projects", "/hub");

        assertThat(inspection.applicationPath()).isEqualTo("/api/admin/projects");
        assertThat(inspection.namespace()).isEqualTo(RequestPathSecurityPolicy.Namespace.API);
        assertThat(inspection.unsafe()).isFalse();
        assertThat(inspection.isPathOrDescendant("/api/admin")).isTrue();
    }

    @Test
    void rejectsEveryRoutingAmbiguityInsideApiAndActuatorNamespaces() {
        assertUnsafe("/api/admin;x=1/projects");
        assertUnsafe("/api/admin%3Bx/projects");
        assertUnsafe("/api/admin%3bx/projects");
        assertUnsafe("/api/%61dmin/projects");
        assertUnsafe("/%61pi/admin/projects");
        assertUnsafe("/api//admin/projects");
        assertUnsafe("/api/./admin/projects");
        assertUnsafe("/api/admin/../projects");
        assertUnsafe("/api\\admin/projects");
        assertUnsafe("/actuator;x=1/info");
        assertUnsafe("/%61ctuator/info");
    }

    @Test
    void leavesCanonicalApiAndActuatorPathsAvailableForNormalClassification() {
        assertThat(RequestPathSecurityPolicy.inspect("/api/admin/projects", "").unsafe()).isFalse();
        assertThat(RequestPathSecurityPolicy.inspect("/api/public/counters", "").unsafe()).isFalse();
        assertThat(RequestPathSecurityPolicy.inspect("/actuator/health", "").unsafe()).isFalse();
    }

    @Test
    void rejectsAnInvalidServletContextContractFailClosed() {
        RequestPathSecurityPolicy.Inspection inspection =
                RequestPathSecurityPolicy.inspect("/api/admin/projects", "/hub");

        assertThat(inspection.unsafe()).isTrue();
    }

    private static void assertUnsafe(String path) {
        assertThat(RequestPathSecurityPolicy.inspect(path, "").unsafe())
                .as(path)
                .isTrue();
    }
}
