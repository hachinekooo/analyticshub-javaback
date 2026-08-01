package com.github.analyticshub.security;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.service.EmailService;
import com.github.analyticshub.util.CryptoUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestPathSecurityIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token-with-sufficient-entropy";
    private static final String PROJECT_ID = "demo_project";
    private static final String API_KEY = "ak_ordinary_device";
    private static final String SECRET_KEY = "sk_ordinary_device_secret";
    private static final String DEVICE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String USER_ID = "22222222-2222-4222-8222-222222222222";

    private SecurityProbeController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        MultiDataSourceManager dataSourceManager = authenticatedProjectManager();
        ClientIpResolver clientIpResolver = new ClientIpResolver("127.0.0.1,::1");
        TwoFactorAuthService twoFactorAuthService = mock(TwoFactorAuthService.class);
        when(twoFactorAuthService.isEnabled()).thenReturn(false);

        PublicEndpointRateLimitFilter publicFilter = new PublicEndpointRateLimitFilter(
                objectMapper,
                clientIpResolver,
                true,
                100,
                60_000
        );
        AdminApiAuthenticationFilter adminFilter = new AdminApiAuthenticationFilter(
                objectMapper,
                new RateLimitService(),
                mock(EmailService.class),
                twoFactorAuthService,
                clientIpResolver,
                ADMIN_TOKEN
        );
        ApiAuthenticationFilter apiFilter = new ApiAuthenticationFilter(
                dataSourceManager,
                objectMapper,
                300_000,
                1_048_576
        );

        controller = new SecurityProbeController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setPatternParser(new PathPatternParser())
                .addFilters(publicFilter, adminFilter, apiFilter)
                .build();
    }

    @Test
    void springPathPatternRoutesAMatrixParameterizedAdminSegmentWithoutTheSecurityFilters() throws Exception {
        SecurityProbeController unfilteredController = new SecurityProbeController();
        MockMvc unfiltered = MockMvcBuilders.standaloneSetup(unfilteredController)
                .setPatternParser(new PathPatternParser())
                .build();

        unfiltered.perform(get(URI.create("/api/admin;x=1/probe")))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(unfilteredController.adminCalls.get()).isEqualTo(1);
    }

    @Test
    void rawMatrixPathsRejectAnonymousRequestsBeforeAdminOrActuatorControllers() throws Exception {
        mockMvc.perform(get(URI.create("/api/admin;x=1/probe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PATH"));
        mockMvc.perform(get(URI.create("/actuator;x=1/info")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PATH"));

        org.assertj.core.api.Assertions.assertThat(controller.adminCalls.get()).isZero();
        org.assertj.core.api.Assertions.assertThat(controller.actuatorCalls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin;x=1/probe",
            "/api/admin%3Bx/probe",
            "/api/admin%3bx/probe",
            "/api/%61dmin/probe",
            "/%61pi/admin/probe",
            "/api//admin/probe",
            "/api/./admin/probe"
    })
    void ambiguousAdminPathsRejectEvenAValidOrdinaryDeviceHmac(String path) throws Exception {
        mockMvc.perform(signedGet(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PATH"));

        org.assertj.core.api.Assertions.assertThat(controller.adminCalls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator;x=1/info",
            "/actuator%3Bx/info",
            "/%61ctuator/info",
            "/actuator//info"
    })
    void ambiguousActuatorPathsRejectEvenAValidOrdinaryDeviceHmac(String path) throws Exception {
        mockMvc.perform(signedGet(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PATH"));

        org.assertj.core.api.Assertions.assertThat(controller.actuatorCalls.get()).isZero();
    }

    @Test
    void contextPathCannotMoveAnAdminOrActuatorEndpointOutOfAdminPolicy() throws Exception {
        mockMvc.perform(signedGet("/hub/api/admin/probe").contextPath("/hub"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_TOKEN_MISSING"));
        mockMvc.perform(signedGet("/hub/actuator/info").contextPath("/hub"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_TOKEN_MISSING"));

        org.assertj.core.api.Assertions.assertThat(controller.adminCalls.get()).isZero();
        org.assertj.core.api.Assertions.assertThat(controller.actuatorCalls.get()).isZero();
    }

    @Test
    void canonicalPathsKeepTheirExistingAuthenticationContracts() throws Exception {
        mockMvc.perform(signedGet("/api/v1/protected/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("device"));
        mockMvc.perform(get("/api/admin/probe").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("admin"));
        mockMvc.perform(get("/actuator/info").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("actuator"));
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        org.assertj.core.api.Assertions.assertThat(controller.deviceCalls.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(controller.adminCalls.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(controller.actuatorCalls.get()).isEqualTo(1);
    }

    @Test
    void canonicalAdminAndActuatorPathsAlsoWorkUnderAContextPath() throws Exception {
        mockMvc.perform(get("/hub/api/admin/probe")
                        .contextPath("/hub")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/hub/actuator/info")
                        .contextPath("/hub")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(controller.adminCalls.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(controller.actuatorCalls.get()).isEqualTo(1);
    }

    @Test
    void canonicalDeviceHmacAlsoWorksUnderAContextPath() throws Exception {
        mockMvc.perform(signedGet("/hub/api/v1/protected/probe").contextPath("/hub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("device"));

        org.assertj.core.api.Assertions.assertThat(controller.deviceCalls.get()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder signedGet(String path) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String signatureData = CryptoUtils.buildSignatureData(
                "GET",
                path,
                timestamp,
                DEVICE_ID,
                USER_ID,
                ""
        );
        return get(URI.create(path))
                .header("X-Project-ID", PROJECT_ID)
                .header("X-API-Key", API_KEY)
                .header("X-Device-ID", DEVICE_ID)
                .header("X-User-ID", USER_ID)
                .header("X-Timestamp", timestamp)
                .header("X-Signature", CryptoUtils.generateSignature(signatureData, SECRET_KEY));
    }

    private static MultiDataSourceManager authenticatedProjectManager() throws Exception {
        MultiDataSourceManager manager = mock(MultiDataSourceManager.class);
        DataSource dataSource = authenticatedDeviceDataSource();
        MultiDataSourceManager.ProjectConfig projectConfig = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Demo Project",
                "localhost",
                5432,
                "demo",
                "analytics",
                "demo",
                "unused",
                "analytics_",
                true
        );
        when(manager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(manager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(manager.getTableName(PROJECT_ID, "devices")).thenReturn("analytics_devices");
        return manager;
    }

    private static DataSource authenticatedDeviceDataSource() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong("id")).thenReturn(1L);
        when(resultSet.getString("device_id")).thenReturn(DEVICE_ID);
        when(resultSet.getString("api_key")).thenReturn(API_KEY);
        when(resultSet.getString("secret_key")).thenReturn(SECRET_KEY);
        when(resultSet.getString("device_model")).thenReturn("integration-test-device");
        when(resultSet.getString("os_version")).thenReturn("test-os");
        when(resultSet.getString("app_version")).thenReturn("1.0.0");
        when(resultSet.getString("project_id")).thenReturn(PROJECT_ID);
        when(resultSet.getBoolean("is_banned")).thenReturn(false);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
        when(resultSet.getTimestamp("last_active_at")).thenReturn(Timestamp.from(Instant.now()));
        return dataSource;
    }

    @RestController
    static final class SecurityProbeController {
        private final AtomicInteger adminCalls = new AtomicInteger();
        private final AtomicInteger actuatorCalls = new AtomicInteger();
        private final AtomicInteger deviceCalls = new AtomicInteger();

        @GetMapping("/api/admin/probe")
        Map<String, String> admin() {
            adminCalls.incrementAndGet();
            return Map.of("kind", "admin");
        }

        @GetMapping("/actuator/info")
        Map<String, String> actuatorInfo() {
            actuatorCalls.incrementAndGet();
            return Map.of("kind", "actuator");
        }

        @GetMapping("/actuator/health")
        Map<String, String> actuatorHealth() {
            return Map.of("status", "UP");
        }

        @GetMapping("/api/v1/protected/probe")
        Map<String, String> device(HttpServletRequest request) {
            deviceCalls.incrementAndGet();
            return Map.of("kind", "device", "uri", request.getRequestURI());
        }
    }
}
