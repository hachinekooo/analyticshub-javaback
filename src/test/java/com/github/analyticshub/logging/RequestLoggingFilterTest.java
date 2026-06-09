package com.github.analyticshub.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestLoggingFilterTest {

    @Test
    void maskIpRedactsStableClientIdentifiers() {
        assertEquals("203.0.113.***", RequestLoggingFilter.maskIp("203.0.113.42"));
        assertEquals("2001:db8:***", RequestLoggingFilter.maskIp("2001:db8:85a3::8a2e:370:7334"));
        assertEquals("***", RequestLoggingFilter.maskIp("::1"));
        assertEquals("-", RequestLoggingFilter.maskIp(""));
        assertEquals("***", RequestLoggingFilter.maskIp("not-an-ip"));
    }
}
