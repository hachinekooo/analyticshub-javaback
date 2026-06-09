package com.github.analyticshub.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
