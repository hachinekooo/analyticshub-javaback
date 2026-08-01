package com.github.analyticshub.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1,10.0.0.0/8");

    @Test
    void ignoresForwardedHeadersFromUntrustedDirectClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void walksForwardedChainFromTrustedProxyTowardClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.8, 198.51.100.30, 10.1.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.30");
    }

    @Test
    void rejectsHostnamesWithoutDoingDnsResolution() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "attacker.example.com");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
