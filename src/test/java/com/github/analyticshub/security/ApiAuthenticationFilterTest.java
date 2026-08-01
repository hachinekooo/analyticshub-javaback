package com.github.analyticshub.security;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ApiAuthenticationFilterTest {

    @Test
    void userIdAcceptsOnlyStandardUuid() {
        assertTrue(ApiAuthenticationFilter.isValidUserId("11111111-1111-4111-8111-111111111111"));

        assertFalse(ApiAuthenticationFilter.isValidUserId("11111111111141118111111111111111"));
        assertFalse(ApiAuthenticationFilter.isValidUserId("cloud_user:11111111-1111-4111-8111-111111111111"));
        assertFalse(ApiAuthenticationFilter.isValidUserId(""));
        assertFalse(ApiAuthenticationFilter.isValidUserId(" user"));
        assertFalse(ApiAuthenticationFilter.isValidUserId("user@example.com"));
        assertFalse(ApiAuthenticationFilter.isValidUserId("user/../token"));
    }

    @Test
    void credentialRotationIsAuthenticatedWhileInitialRegistrationIsPublic() {
        assertTrue(ApiAuthenticationFilter.isPublicPath("/api/v1/auth/register"));
        assertFalse(ApiAuthenticationFilter.isPublicPath("/api/v1/auth/credentials/rotate"));
    }

    @Test
    void oversizedAuthenticatedRequestReturnsPayloadTooLargeBeforeDatabaseAccess() throws Exception {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(
                mock(MultiDataSourceManager.class),
                JsonMapper.builder().build(),
                300_000,
                4
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events/track");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("REQUEST_BODY_TOO_LARGE"));
    }
}
