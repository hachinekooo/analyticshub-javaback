package com.github.analyticshub.logging;

import com.github.analyticshub.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestLoggingFilterTest {

    @Test
    void invalidRequestIdIsReplacedBeforeItIsEchoed() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(new ClientIpResolver(""));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/health\nFORGED_PATH"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        String maliciousRequestId = "trusted-id\r\nX-Forged: yes";
        request.addHeader("X-Request-Id", maliciousRequestId);
        request.addHeader("X-Project-ID", "project_1\r\nFORGED_PROJECT");

        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(RequestLoggingFilter.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);

        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> { });
        } finally {
            logger.removeHandler(handler);
        }

        String assignedRequestId = response.getHeader("X-Request-Id");
        assertNotEquals(maliciousRequestId, assignedRequestId);
        UUID.fromString(assignedRequestId);
        assertThat(messages).singleElement().asString()
                .contains("HTTP GET <invalid-path>")
                .contains("projectId=<invalid>")
                .contains("requestId=" + assignedRequestId)
                .doesNotContain("FORGED_PATH")
                .doesNotContain("FORGED_PROJECT")
                .doesNotContain("X-Forged");
    }

    @Test
    void validBoundedRequestIdIsPreserved() {
        String requestId = "trace-01_abc.def:123";

        assertEquals(requestId, LogValueSanitizer.requestIdOrRandom(requestId));
    }

    @Test
    void clientControlledLogValuesUseStrictSingleLineBounds() {
        assertEquals("/api/v1/events/%2F", LogValueSanitizer.path("/api/v1/events/%2F"));
        assertEquals(LogValueSanitizer.INVALID_PATH,
                LogValueSanitizer.path("/api/events\nFORGED"));
        assertEquals(LogValueSanitizer.INVALID_PATH,
                LogValueSanitizer.path("/api/" + "a".repeat(600)));

        assertEquals("project_1", LogValueSanitizer.projectId("project_1"));
        assertEquals(LogValueSanitizer.INVALID_VALUE,
                LogValueSanitizer.projectId("project_1\r\nFORGED"));
        assertEquals(LogValueSanitizer.INVALID_VALUE,
                LogValueSanitizer.projectId("p".repeat(51)));

        assertEquals("item.completed:v2", LogValueSanitizer.eventType("item.completed:v2"));
        assertEquals(LogValueSanitizer.INVALID_VALUE,
                LogValueSanitizer.eventType("item_completed\u2028FORGED"));
        assertEquals(LogValueSanitizer.INVALID_VALUE,
                LogValueSanitizer.eventType("e".repeat(101)));
    }

    @Test
    void maskIpRedactsStableClientIdentifiers() {
        assertEquals("203.0.113.***", RequestLoggingFilter.maskIp("203.0.113.42"));
        assertEquals("2001:db8:***", RequestLoggingFilter.maskIp("2001:db8:85a3::8a2e:370:7334"));
        assertEquals("***", RequestLoggingFilter.maskIp("::1"));
        assertEquals("-", RequestLoggingFilter.maskIp(""));
        assertEquals("***", RequestLoggingFilter.maskIp("not-an-ip"));
    }
}
