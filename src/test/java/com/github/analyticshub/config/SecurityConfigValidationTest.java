package com.github.analyticshub.config;

import com.github.analyticshub.security.ClientIpResolver;
import com.github.analyticshub.security.RateLimitService;
import com.github.analyticshub.security.TwoFactorAuthService;
import com.github.analyticshub.service.EmailService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SecurityConfigValidationTest {

    @Test
    void actorLinkClientsRequireUniqueProjectScopedCredentialsWithoutWhitespace() {
        ActorLinkSecurityProperties properties = new ActorLinkSecurityProperties();
        properties.setEnabled(true);
        properties.setClients(List.of(
                new ActorLinkSecurityProperties.Client(
                        "backend-test",
                        "project-test",
                        " " + "a".repeat(32)
                )
        ));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unpadded secret");

        properties.setClients(List.of(
                new ActorLinkSecurityProperties.Client("backend", "project", "a".repeat(32)),
                new ActorLinkSecurityProperties.Client("backend", "project", "b".repeat(32))
        ));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one project");

        properties.setClients(List.of(
                new ActorLinkSecurityProperties.Client("backend-test", "project-test", "a".repeat(32)),
                new ActorLinkSecurityProperties.Client("backend-prod", "project-prod", "a".repeat(32))
        ));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secrets must be unique");

        properties.setClients(List.of(
                new ActorLinkSecurityProperties.Client("backend-test", "project-shared", "a".repeat(32)),
                new ActorLinkSecurityProperties.Client("backend-prod", "project-shared", "b".repeat(32))
        ));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projectId may authorize exactly one service client");
    }

    @Test
    void actorLinkSignatureValidityHasABoundedReplayWindow() {
        ActorLinkSecurityProperties properties = new ActorLinkSecurityProperties();
        properties.setSignatureValidityMs(ActorLinkSecurityProperties.MAX_SIGNATURE_VALIDITY_MS + 1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signature validity is capped");
    }

    @Test
    void blankAdminTokenKeepsAdminEndpointsDisabledWithoutBlockingStartup() {
        assertThatCode(() -> SecurityConfig.validateAdminTokenConfiguration(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> SecurityConfig.validateAdminTokenConfiguration("   "))
                .doesNotThrowAnyException();
    }

    @Test
    void configuredAdminTokenMustHaveAtLeastThirtyTwoCharacters() {
        String validToken = "a".repeat(SecurityConfig.MIN_ADMIN_TOKEN_LENGTH);

        assertThatCode(() -> SecurityConfig.validateAdminTokenConfiguration(validToken))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SecurityConfig.validateAdminTokenConfiguration("a".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void configuredAdminTokenRejectsSurroundingWhitespace() {
        String token = "a".repeat(SecurityConfig.MIN_ADMIN_TOKEN_LENGTH);

        assertThatThrownBy(() -> SecurityConfig.validateAdminTokenConfiguration(" " + token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no surrounding whitespace");
        assertThatThrownBy(() -> SecurityConfig.validateAdminTokenConfiguration(token + "\t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no surrounding whitespace");
    }

    @Test
    void validationFailureDoesNotExposeConfiguredToken() {
        String configuredToken = "do-not-log-this-token";

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> SecurityConfig.validateAdminTokenConfiguration(configuredToken)
        );

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage()).doesNotContain(configuredToken);
    }

    @Test
    void liveFirewallRejectionUsesTheBoundedApiErrorShape() throws Exception {
        SecurityConfig config = securityConfig();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/admin;x=1/projects"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        String rejectedDetail = "must-not-be-returned";

        config.apiRequestRejectedHandler(ObservationRegistry.NOOP).handle(
                request,
                response,
                new RequestRejectedException(rejectedDetail)
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_REQUEST_PATH\"")
                .doesNotContain(rejectedDetail);
    }

    @Test
    void nonPathFirewallRejectionDoesNotExposeTheFrameworkException() throws Exception {
        SecurityConfig config = securityConfig();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String rejectedDetail = "must-not-be-returned";

        config.apiRequestRejectedHandler(ObservationRegistry.NOOP).handle(
                request,
                response,
                new RequestRejectedException(rejectedDetail)
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_REQUEST\"")
                .doesNotContain(rejectedDetail);
    }

    private static SecurityConfig securityConfig() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return new SecurityConfig(
                objectMapper,
                mock(MultiDataSourceManager.class),
                new RateLimitService(),
                mock(EmailService.class),
                mock(TwoFactorAuthService.class),
                new ClientIpResolver("127.0.0.1,::1"),
                new ActorLinkSecurityProperties()
        );
    }
}
