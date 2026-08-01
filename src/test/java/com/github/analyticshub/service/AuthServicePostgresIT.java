package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.AdminDeviceCredentialResetResponse;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.security.ApiAuthenticationFilter;
import com.github.analyticshub.util.CryptoUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AuthServicePostgresIT {

    private static final String PROJECT_ID = "auth_project";
    private static final String PREFIX = "auth_";
    private static final String DEVICE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String USER_ID = "22222222-2222-4222-8222-222222222222";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("auth_service_test")
            .withUsername("auth_test")
            .withPassword("auth_test_password");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AuthService authService;
    private MultiDataSourceManager dataSourceManager;

    @BeforeEach
    void setUp() {
        String schema = "auth_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setDriverClassName("org.postgresql.Driver");
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        dataSourceManager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig projectConfig = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Auth Test",
                "localhost",
                5432,
                "auth_service_test",
                schema,
                "auth_test",
                "auth_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "devices"))
                .thenReturn(quoted(PREFIX + "devices"));

        authService = new AuthService(dataSourceManager, new ProjectTransactionExecutor(), false, 600);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void firstRegistrationPersistsIssuedCredentials() {
        DeviceRegisterResponse response = authService.registerDevice(PROJECT_ID, registerRequest());

        assertThat(response.isNew()).isTrue();
        assertThat(response.apiKey()).startsWith("ak_");
        assertThat(response.secretKey()).startsWith("sk_");
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(storedCredentials()).isEqualTo(new StoredCredentials(
                response.apiKey(),
                response.secretKey()
        ));
    }

    @Test
    void duplicatePublicRegistrationIsRejectedWithoutChangingStoredCredentials() {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        StoredCredentials before = storedCredentials();

        assertThatThrownBy(() -> authService.registerDevice(PROJECT_ID, registerRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DEVICE_ALREADY_REGISTERED");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(409);
                });

        assertThat(deviceCount()).isEqualTo(1);
        assertThat(storedCredentials()).isEqualTo(before);
        assertThat(before).isEqualTo(new StoredCredentials(first.apiKey(), first.secretKey()));
    }

    @Test
    void authenticatedRequestContextCanRotateCredentials() {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);

        DeviceRegisterResponse rotated = authService.rotateCredentials();

        assertThat(rotated.isNew()).isFalse();
        assertThat(rotated.apiKey()).startsWith("ak_").isNotEqualTo(first.apiKey());
        assertThat(rotated.secretKey()).startsWith("sk_").isNotEqualTo(first.secretKey());
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(storedCredentials()).isEqualTo(new StoredCredentials(
                rotated.apiKey(),
                rotated.secretKey()
        ));
    }

    @Test
    void lostRotationResponseCanBeRecoveredWithPreviousCredentials() {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        DeviceRegisterResponse rotated = authService.rotateCredentials();

        // Simulate a lost HTTP response: the client still owns only the old
        // pair and retries the same authenticated operation.
        setAuthenticatedRequestContext(first);
        DeviceRegisterResponse recovered = authService.rotateCredentials();

        assertThat(recovered).isEqualTo(rotated);
        assertThat(storedCredentials()).isEqualTo(new StoredCredentials(
                rotated.apiKey(),
                rotated.secretKey()
        ));
    }

    @Test
    void previousCredentialsPassHmacFilterAndRecoverLostRotationResponse() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        DeviceRegisterResponse rotated = authService.rotateCredentials();
        RequestContext.clear();

        String path = "/api/v1/auth/credentials/rotate";
        String timestamp = Long.toString(System.currentTimeMillis());
        String signatureData = CryptoUtils.buildSignatureData(
                "POST", path, timestamp, DEVICE_ID, USER_ID, ""
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Project-ID", PROJECT_ID);
        request.addHeader("X-API-Key", first.apiKey());
        request.addHeader("X-Device-ID", DEVICE_ID);
        request.addHeader("X-User-ID", USER_ID);
        request.addHeader("X-Timestamp", timestamp);
        request.addHeader("X-Signature", CryptoUtils.generateSignature(signatureData, first.secretKey()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<DeviceRegisterResponse> recovered = new AtomicReference<>();

        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(
                dataSourceManager,
                JsonMapper.builder().build(),
                300_000,
                1_048_576
        );
        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                recovered.set(authService.rotateCredentials()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recovered.get()).isEqualTo(rotated);
        assertThat(storedCredentials()).isEqualTo(new StoredCredentials(
                rotated.apiKey(),
                rotated.secretKey()
        ));
    }

    @Test
    void previousCredentialsCanAuthenticateNonRotationHmacApiDuringGraceWindow() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        authService.rotateCredentials();
        RequestContext.clear();

        AuthenticationResult result = authenticate(
                first.apiKey(),
                first.secretKey(),
                "/api/v1/events/track",
                "{}"
        );

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.chainInvoked()).isTrue();
    }

    @Test
    void expiredPreviousCredentialsFailHmacAuthentication() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        authService.rotateCredentials();
        RequestContext.clear();
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "devices") +
                        " SET previous_credentials_expires_at = NOW() - INTERVAL '1 second' " +
                        "WHERE project_id = ? AND device_id = ?::uuid",
                PROJECT_ID,
                DEVICE_ID
        );

        AuthenticationResult result = authenticate(
                first.apiKey(),
                first.secretKey(),
                "/api/v1/events/track",
                "{}"
        );

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainInvoked()).isFalse();
        assertThat(result.body()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void wrongPreviousSecretFailsHmacAuthentication() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        authService.rotateCredentials();
        RequestContext.clear();

        AuthenticationResult result = authenticate(
                first.apiKey(),
                "sk_wrong_previous_secret",
                "/api/v1/events/track",
                "{}"
        );

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainInvoked()).isFalse();
        assertThat(result.body()).contains("INVALID_SIGNATURE");
    }

    @Test
    void secondRotationInvalidatesTheOldestGenerationAndKeepsOnlyImmediatePrevious() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        DeviceRegisterResponse second = authService.rotateCredentials();
        setAuthenticatedRequestContext(second);
        authService.rotateCredentials();
        RequestContext.clear();

        AuthenticationResult oldest = authenticate(
                first.apiKey(),
                first.secretKey(),
                "/api/v1/events/track",
                "{}"
        );
        AuthenticationResult immediatePrevious = authenticate(
                second.apiKey(),
                second.secretKey(),
                "/api/v1/events/track",
                "{}"
        );

        assertThat(oldest.status()).isEqualTo(401);
        assertThat(oldest.chainInvoked()).isFalse();
        assertThat(oldest.body()).contains("INVALID_CREDENTIALS");
        assertThat(immediatePrevious.status()).isEqualTo(200);
        assertThat(immediatePrevious.chainInvoked()).isTrue();
    }

    @Test
    void adminResetAtomicallyReissuesCredentialsAndRevokesCurrentAndPreviousPairs() throws Exception {
        DeviceRegisterResponse first = authService.registerDevice(PROJECT_ID, registerRequest());
        setAuthenticatedRequestContext(first);
        DeviceRegisterResponse rotated = authService.rotateCredentials();
        RequestContext.clear();

        AdminDeviceCredentialResetResponse reset = authService.resetCredentialsByAdmin(PROJECT_ID, DEVICE_ID);
        StoredCredentialState state = storedCredentialState();

        assertThat(reset.projectId()).isEqualTo(PROJECT_ID);
        assertThat(reset.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(reset.apiKey()).startsWith("ak_").isNotEqualTo(rotated.apiKey());
        assertThat(reset.secretKey()).startsWith("sk_").isNotEqualTo(rotated.secretKey());
        assertThat(state.apiKey()).isEqualTo(reset.apiKey());
        assertThat(state.secretKey()).isEqualTo(reset.secretKey());
        assertThat(state.previousApiKey()).isNull();
        assertThat(state.previousSecretKey()).isNull();
        assertThat(state.previousExpiresAt()).isNull();

        AuthenticationResult original = authenticate(
                first.apiKey(), first.secretKey(), "/api/v1/events/track", "{}"
        );
        AuthenticationResult preResetCurrent = authenticate(
                rotated.apiKey(), rotated.secretKey(), "/api/v1/events/track", "{}"
        );
        AuthenticationResult recovered = authenticate(
                reset.apiKey(), reset.secretKey(), "/api/v1/events/track", "{}"
        );
        assertThat(original.status()).isEqualTo(401);
        assertThat(preResetCurrent.status()).isEqualTo(401);
        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.chainInvoked()).isTrue();
    }

    @Test
    void adminResetReturnsStableNotFoundForUnknownDevice() {
        String unknownDeviceId = "33333333-3333-4333-8333-333333333333";

        assertThatThrownBy(() -> authService.resetCredentialsByAdmin(PROJECT_ID, unknownDeviceId))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DEVICE_NOT_FOUND");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void concurrentFirstRegistrationsHaveOneWinnerAndNeverOverwriteItsCredentials() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RegistrationAttempt> first = executor.submit(() -> registerAfter(ready, start));
            Future<RegistrationAttempt> second = executor.submit(() -> registerAfter(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RegistrationAttempt firstAttempt = first.get(10, TimeUnit.SECONDS);
            RegistrationAttempt secondAttempt = second.get(10, TimeUnit.SECONDS);
            RegistrationAttempt winner = firstAttempt.response() != null ? firstAttempt : secondAttempt;
            RegistrationAttempt loser = firstAttempt.error() != null ? firstAttempt : secondAttempt;
            int successCount = (firstAttempt.response() != null ? 1 : 0)
                    + (secondAttempt.response() != null ? 1 : 0);
            int conflictCount = (firstAttempt.error() != null ? 1 : 0)
                    + (secondAttempt.error() != null ? 1 : 0);

            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
            assertThat(loser.error().getCode()).isEqualTo("DEVICE_ALREADY_REGISTERED");
            assertThat(loser.error().getHttpStatus().value()).isEqualTo(409);
            assertThat(winner.response().isNew()).isTrue();
            assertThat(deviceCount()).isEqualTo(1);
            assertThat(storedCredentials()).isEqualTo(new StoredCredentials(
                    winner.response().apiKey(),
                    winner.response().secretKey()
            ));
        } finally {
            executor.shutdownNow();
        }
    }

    private RegistrationAttempt registerAfter(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return new RegistrationAttempt(authService.registerDevice(PROJECT_ID, registerRequest()), null);
        } catch (BusinessException exception) {
            return new RegistrationAttempt(null, exception);
        }
    }

    private void setAuthenticatedRequestContext(DeviceRegisterResponse credentials) {
        Device device = new Device();
        device.setDeviceId(UUID.fromString(DEVICE_ID));
        device.setApiKey(credentials.apiKey());
        device.setSecretKey(credentials.secretKey());

        RequestContext context = new RequestContext();
        context.setProjectId(PROJECT_ID);
        context.setDevice(device);
        context.setDataSource(dataSource);
        context.setTablePrefix(PREFIX);
        RequestContext.set(context);
    }

    private StoredCredentials storedCredentials() {
        return jdbcTemplate.queryForObject(
                "SELECT api_key, secret_key FROM " + quoted(PREFIX + "devices")
                        + " WHERE project_id = ? AND device_id = ?::uuid",
                (resultSet, rowNumber) -> new StoredCredentials(
                        resultSet.getString("api_key"),
                        resultSet.getString("secret_key")
                ),
                PROJECT_ID,
                DEVICE_ID
        );
    }

    private StoredCredentialState storedCredentialState() {
        return jdbcTemplate.queryForObject(
                "SELECT api_key, secret_key, previous_api_key, previous_secret_key, " +
                        "previous_credentials_expires_at::text AS previous_expires_at FROM " +
                        quoted(PREFIX + "devices") +
                        " WHERE project_id = ? AND device_id = ?::uuid",
                (resultSet, rowNumber) -> new StoredCredentialState(
                        resultSet.getString("api_key"),
                        resultSet.getString("secret_key"),
                        resultSet.getString("previous_api_key"),
                        resultSet.getString("previous_secret_key"),
                        resultSet.getString("previous_expires_at")
                ),
                PROJECT_ID,
                DEVICE_ID
        );
    }

    private AuthenticationResult authenticate(
            String apiKey,
            String secretKey,
            String path,
            String body
    ) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis());
        String signatureData = CryptoUtils.buildSignatureData(
                "POST", path, timestamp, DEVICE_ID, USER_ID, body
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Project-ID", PROJECT_ID);
        request.addHeader("X-API-Key", apiKey);
        request.addHeader("X-Device-ID", DEVICE_ID);
        request.addHeader("X-User-ID", USER_ID);
        request.addHeader("X-Timestamp", timestamp);
        request.addHeader("X-Signature", CryptoUtils.generateSignature(signatureData, secretKey));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(
                dataSourceManager,
                JsonMapper.builder().build(),
                300_000,
                1_048_576
        );
        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> chainInvoked.set(true));
        return new AuthenticationResult(
                response.getStatus(),
                response.getContentAsString(),
                chainInvoked.get()
        );
    }

    private long deviceCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoted(PREFIX + "devices"),
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static DeviceRegisterRequest registerRequest() {
        return new DeviceRegisterRequest(DEVICE_ID, "iPhone", "iOS 26.0", "1.0.0");
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record StoredCredentials(String apiKey, String secretKey) {
    }

    private record StoredCredentialState(
            String apiKey,
            String secretKey,
            String previousApiKey,
            String previousSecretKey,
            String previousExpiresAt
    ) {}

    private record AuthenticationResult(int status, String body, boolean chainInvoked) {}

    private record RegistrationAttempt(DeviceRegisterResponse response, BusinessException error) {
    }
}
