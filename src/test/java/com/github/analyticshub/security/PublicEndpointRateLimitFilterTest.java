package com.github.analyticshub.security;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PublicEndpointRateLimitFilterTest {

    @Test
    void limitsAnonymousEndpointsByResolvedClientIp() throws Exception {
        PublicEndpointRateLimitFilter filter = new PublicEndpointRateLimitFilter(
                JsonMapper.builder().build(),
                new ClientIpResolver("127.0.0.1"),
                true,
                2,
                60_000,
                Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
        );
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest first = publicRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);

        MockHttpServletRequest second = publicRequest();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);

        MockHttpServletRequest third = publicRequest();
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(thirdResponse.getStatus()).isEqualTo(429);
        assertThat(thirdResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(thirdResponse.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void ignoresAuthenticatedCollectionAndCorsPreflight() throws Exception {
        PublicEndpointRateLimitFilter filter = new PublicEndpointRateLimitFilter(
                JsonMapper.builder().build(),
                new ClientIpResolver(""),
                true,
                1,
                60_000
        );
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest collection = new MockHttpServletRequest("POST", "/api/v1/events/track");
        filter.doFilter(collection, new MockHttpServletResponse(), chain);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/public/traffic/track");
        filter.doFilter(preflight, new MockHttpServletResponse(), chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest publicRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/traffic/track");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        return request;
    }
}
