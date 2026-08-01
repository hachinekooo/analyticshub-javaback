package com.github.analyticshub.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingHttpServletRequestWrapperTest {

    @Test
    void acceptsAnExactLimitBodyAndKeepsItRepeatable() throws Exception {
        byte[] body = "12345678".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);

        CachingHttpServletRequestWrapper wrapper =
                new CachingHttpServletRequestWrapper(request, body.length);

        assertThat(wrapper.getBody()).isEqualTo("12345678");
        assertThat(wrapper.getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(wrapper.getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void rejectsADeclaredBodyThatExceedsTheLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new CachingHttpServletRequestWrapper(request, 4))
                .isInstanceOf(CachingHttpServletRequestWrapper.RequestBodyTooLargeException.class);
    }

    @Test
    void rejectsAChunkedBodyThatExceedsTheLimitWhileStreaming() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new CachingHttpServletRequestWrapper(request, 4))
                .isInstanceOf(CachingHttpServletRequestWrapper.RequestBodyTooLargeException.class);
    }
}
