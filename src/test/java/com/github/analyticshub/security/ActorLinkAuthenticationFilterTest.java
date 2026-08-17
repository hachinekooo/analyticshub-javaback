package com.github.analyticshub.security;

import com.github.analyticshub.config.ActorLinkSecurityProperties;
import com.github.analyticshub.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActorLinkAuthenticationFilterTest {

    private static final String SERVICE_ID = "backend-test";
    private static final String PROJECT_ID = "project-test";
    private static final String SECRET = "actor-link-test-secret-with-at-least-32-characters";
    private static final String PROD_SERVICE_ID = "backend-prod";
    private static final String PROD_PROJECT_ID = "project-prod";
    private static final String PROD_SECRET = "actor-link-prod-secret-with-at-least-32-characters";
    private static final String BODY = "{\"bindingId\":\"11111111-1111-4111-8111-111111111111\"}";

    private ProbeController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ActorLinkSecurityProperties properties = new ActorLinkSecurityProperties();
        properties.setEnabled(true);
        properties.setRequireLoopback(true);
        properties.setClients(List.of(
                new ActorLinkSecurityProperties.Client(SERVICE_ID, PROJECT_ID, SECRET),
                new ActorLinkSecurityProperties.Client(PROD_SERVICE_ID, PROD_PROJECT_ID, PROD_SECRET)
        ));
        properties.validate();

        controller = new ProbeController();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilter(new ActorLinkAuthenticationFilter(objectMapper, properties))
                .build();
    }

    @Test
    void validLoopbackRequestReachesTheController() throws Exception {
        mockMvc.perform(signedRequest(PROJECT_ID, System.currentTimeMillis()).with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    void oneHubAcceptsEachBackendOnlyForItsConfiguredProject() throws Exception {
        mockMvc.perform(signedRequest(
                        PROD_SERVICE_ID,
                        PROD_PROJECT_ID,
                        PROD_SECRET,
                        System.currentTimeMillis()
                ))
                .andExpect(status().isOk());

        mockMvc.perform(signedRequest(
                        SERVICE_ID,
                        PROD_PROJECT_ID,
                        SECRET,
                        System.currentTimeMillis()
                ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTOR_LINK_CLIENT_FORBIDDEN"));
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    void projectScopeSignatureAndFreshnessAreEnforced() throws Exception {
        mockMvc.perform(signedRequest(PROD_PROJECT_ID, System.currentTimeMillis()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTOR_LINK_CLIENT_FORBIDDEN"));
        mockMvc.perform(signedRequest(PROJECT_ID, System.currentTimeMillis() - 600_000))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACTOR_LINK_TIMESTAMP_INVALID"));
        assertThat(controller.calls.get()).isZero();
    }

    @Test
    void freshnessRejectsOverflowingExtremeTimestampsAndKeepsInclusiveBounds() {
        long validityMs = 300_000;
        long now = System.currentTimeMillis();

        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MIN_VALUE, now, validityMs)).isFalse();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MAX_VALUE, now, validityMs)).isFalse();
        assertThat(ActorLinkAuthenticationFilter.isFresh(now - validityMs, now, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(now + validityMs, now, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(now - validityMs - 1, now, validityMs)).isFalse();
        assertThat(ActorLinkAuthenticationFilter.isFresh(now + validityMs + 1, now, validityMs)).isFalse();
    }

    @Test
    void freshnessUsesSaturatingBoundsAtLongExtremes() {
        long validityMs = 10;

        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MIN_VALUE, Long.MIN_VALUE, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MIN_VALUE + validityMs, Long.MIN_VALUE, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MIN_VALUE + validityMs + 1, Long.MIN_VALUE, validityMs)).isFalse();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MAX_VALUE, Long.MAX_VALUE, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MAX_VALUE - validityMs, Long.MAX_VALUE, validityMs)).isTrue();
        assertThat(ActorLinkAuthenticationFilter.isFresh(Long.MAX_VALUE - validityMs - 1, Long.MAX_VALUE, validityMs)).isFalse();
    }

    @Test
    void nonLoopbackAndTamperedBodiesAreRejected() throws Exception {
        mockMvc.perform(signedRequest(PROJECT_ID, System.currentTimeMillis()).with(request -> {
                    request.setRemoteAddr("203.0.113.8");
                    return request;
                }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTOR_LINK_NETWORK_FORBIDDEN"));

        MockHttpServletRequestBuilder tampered = signedRequest(PROJECT_ID, System.currentTimeMillis())
                .content("{\"bindingId\":\"tampered\"}");
        mockMvc.perform(tampered)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACTOR_LINK_SIGNATURE_INVALID"));
        assertThat(controller.calls.get()).isZero();
    }

    @Test
    void bodyDigestUsesTheExactRawBytes() throws Exception {
        byte[] rawBody = new byte[]{'{', '"', 'x', '"', ':', '1', '}', (byte) 0xff};
        String timestamp = Long.toString(System.currentTimeMillis());
        String idempotencyKey = "11111111-1111-4111-8111-111111111111";
        String signatureData = String.join("|",
                "POST", ActorLinkAuthenticationFilter.ENDPOINT, timestamp, SERVICE_ID, PROJECT_ID,
                idempotencyKey, CryptoUtils.sha256Hex(rawBody));

        mockMvc.perform(post(ActorLinkAuthenticationFilter.ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody)
                        .header("X-Service-ID", SERVICE_ID)
                        .header("X-Project-ID", PROJECT_ID)
                        .header("X-Timestamp", timestamp)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .header("X-Service-Signature", CryptoUtils.generateSignature(signatureData, SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder signedRequest(String projectId, long timestamp) {
        return signedRequest(SERVICE_ID, projectId, SECRET, timestamp);
    }

    private MockHttpServletRequestBuilder signedRequest(
            String serviceId,
            String projectId,
            String secret,
            long timestamp
    ) {
        String timestampText = Long.toString(timestamp);
        String idempotencyKey = "11111111-1111-4111-8111-111111111111";
        String signatureData = String.join("|",
                "POST",
                ActorLinkAuthenticationFilter.ENDPOINT,
                timestampText,
                serviceId,
                projectId,
                idempotencyKey,
                CryptoUtils.sha256Hex(BODY)
        );
        return post(ActorLinkAuthenticationFilter.ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Service-ID", serviceId)
                .header("X-Project-ID", projectId)
                .header("X-Timestamp", timestampText)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-Service-Signature", CryptoUtils.generateSignature(signatureData, secret));
    }

    @RestController
    @RequestMapping(ActorLinkAuthenticationFilter.ENDPOINT)
    static final class ProbeController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping
        Map<String, Boolean> link() {
            calls.incrementAndGet();
            return Map.of("accepted", true);
        }
    }
}
