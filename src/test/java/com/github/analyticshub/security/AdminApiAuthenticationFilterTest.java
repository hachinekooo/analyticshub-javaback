package com.github.analyticshub.security;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.service.EmailService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminApiAuthenticationFilterTest {

    private static final String ADMIN_TOKEN = "test-admin-token-with-sufficient-entropy";

    private RateLimitService rateLimitService;
    private FilterChain filterChain;
    private AtomicBoolean chainInvoked;
    private AdminApiAuthenticationFilter filter;
    private TwoFactorAuthService twoFactorAuthService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
        chainInvoked = new AtomicBoolean(false);
        filterChain = (request, response) -> chainInvoked.set(true);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        EmailService emailService = new EmailService(beanFactory.getBeanProvider(JavaMailSender.class));
        twoFactorAuthService = mock(TwoFactorAuthService.class);
        when(twoFactorAuthService.isEnabled()).thenReturn(false);
        filter = new AdminApiAuthenticationFilter(
                JsonMapper.builder().build(),
                rateLimitService,
                emailService,
                twoFactorAuthService,
                new ClientIpResolver("127.0.0.1,::1"),
                ADMIN_TOKEN
        );
    }

    @Test
    void anonymousActuatorHealthIsPublicAndMinimalPathOnly() throws Exception {
        MockHttpServletRequest request = request("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertTrue(chainInvoked.get());
        assertFalse(AdminApiAuthenticationFilter.requiresAdminAuthentication("/actuator/health"));
        assertTrue(AdminApiAuthenticationFilter.requiresAdminAuthentication("/actuator/health/db"));
    }

    @Test
    void anonymousActuatorInfoIsRejected() throws Exception {
        MockHttpServletRequest request = request("/actuator/info");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_TOKEN_MISSING"));
        assertFalse(chainInvoked.get());
    }

    @Test
    void adminTokenCanAccessProtectedActuatorEndpoint() throws Exception {
        rateLimitService.recordFailure("127.0.0.1");
        MockHttpServletRequest request = request("/actuator/metrics");
        request.addHeader("X-Admin-Token", ADMIN_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(0, rateLimitService.getFailureCount("127.0.0.1"));
        assertTrue(chainInvoked.get());
    }

    @Test
    void actuatorTokenInQueryStringIsRejected() throws Exception {
        MockHttpServletRequest request = request("/actuator/info");
        request.setParameter("token", ADMIN_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_TOKEN_INVALID"));
        assertEquals(1, rateLimitService.getFailureCount("127.0.0.1"));
        assertFalse(chainInvoked.get());
    }

    @Test
    void similarPrefixesDoNotAccidentallyEnterAdminPolicy() {
        assertFalse(AdminApiAuthenticationFilter.requiresAdminAuthentication("/actuator-health"));
        assertFalse(AdminApiAuthenticationFilter.requiresAdminAuthentication("/api/administrator"));
        assertTrue(AdminApiAuthenticationFilter.requiresAdminAuthentication("/actuator"));
        assertTrue(AdminApiAuthenticationFilter.requiresAdminAuthentication("/api/admin/projects"));
    }

    @Test
    void deviceCredentialRecoveryEndpointRequiresAdminAuthentication() throws Exception {
        String path = "/api/admin/projects/demo_project/devices/" +
                "11111111-1111-4111-8111-111111111111/credentials/reset";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertTrue(AdminApiAuthenticationFilter.requiresAdminAuthentication(path));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_TOKEN_MISSING"));
        assertFalse(chainInvoked.get());
    }

    @Test
    void invalidOtpAttemptsAreRateLimitedAfterTokenValidation() throws Exception {
        when(twoFactorAuthService.isEnabled()).thenReturn(true);
        when(twoFactorAuthService.isTrusted("127.0.0.1")).thenReturn(false);
        when(twoFactorAuthService.verifyCode(123456)).thenReturn(false);

        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletRequest request = request("/api/admin/projects");
            request.addHeader("X-Admin-Token", ADMIN_TOKEN);
            request.addHeader("X-Admin-OTP", "123456");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, filterChain);
            assertEquals(403, response.getStatus());
        }

        MockHttpServletRequest bannedRequest = request("/api/admin/projects");
        bannedRequest.addHeader("X-Admin-Token", ADMIN_TOKEN);
        bannedRequest.addHeader("X-Admin-OTP", "123456");
        MockHttpServletResponse bannedResponse = new MockHttpServletResponse();
        filter.doFilter(bannedRequest, bannedResponse, filterChain);

        assertEquals(403, bannedResponse.getStatus());
        assertTrue(bannedResponse.getContentAsString().contains("TOO_MANY_ATTEMPTS"));
        assertEquals(5, rateLimitService.getFailureCount("127.0.0.1"));
        assertFalse(chainInvoked.get());
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
