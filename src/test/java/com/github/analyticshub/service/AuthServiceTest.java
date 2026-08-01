package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PROJECT_ID = "test_project";
    private static final String DEVICE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String DEVICES_TABLE = "\"analytics_devices\"";

    @Mock
    private MultiDataSourceManager dataSourceManager;

    @Mock
    private ProjectTransactionExecutor projectTransactions;

    @Mock
    private DataSource dataSource;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig());
    }

    @Test
    void publicReregistrationRejectsAnExistingDeviceByDefault() {
        stubProjectDatabase();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(DEVICE_ID), eq(PROJECT_ID)))
                .thenReturn(List.of(12L));
        AuthService authService = new AuthService(dataSourceManager, projectTransactions, false, 600);

        assertThatThrownBy(() -> authService.registerDevice(PROJECT_ID, registerRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DEVICE_ALREADY_REGISTERED");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(409);
                });

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void explicitCompatibilityFlagCanRotateExistingCredentials() {
        stubProjectDatabase();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(DEVICE_ID), eq(PROJECT_ID)))
                .thenReturn(List.of(12L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthService authService = new AuthService(dataSourceManager, projectTransactions, true, 600);

        DeviceRegisterResponse response = authService.registerDevice(PROJECT_ID, registerRequest());

        assertThat(response.isNew()).isFalse();
        assertThat(response.apiKey()).startsWith("ak_");
        assertThat(response.secretKey()).startsWith("sk_");
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void firstRegistrationInsertsNewDevice() {
        stubProjectDatabase();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(DEVICE_ID), eq(PROJECT_ID)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthService authService = new AuthService(dataSourceManager, projectTransactions, false, 600);

        DeviceRegisterResponse response = authService.registerDevice(PROJECT_ID, registerRequest());

        assertThat(response.isNew()).isTrue();
        assertThat(response.apiKey()).startsWith("ak_");
        assertThat(response.secretKey()).startsWith("sk_");
    }

    @Test
    void losingAConcurrentFirstRegistrationRaceNeverRotatesTheWinner() {
        stubProjectDatabase();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(DEVICE_ID), eq(PROJECT_ID)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        AuthService authService = new AuthService(dataSourceManager, projectTransactions, true, 600);

        assertThatThrownBy(() -> authService.registerDevice(PROJECT_ID, registerRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DEVICE_ALREADY_REGISTERED"));
    }

    @Test
    void registrationRejectsUuidFormsThatAreNotCanonical() {
        AuthService authService = new AuthService(dataSourceManager, projectTransactions, false, 600);
        DeviceRegisterRequest request = new DeviceRegisterRequest(
                "1-1-1-1-1",
                "iPhone",
                "iOS 26.0",
                "1.0.0"
        );

        assertThatThrownBy(() -> authService.registerDevice(PROJECT_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEVICE_ID"));
    }

    private void stubProjectDatabase() {
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "devices")).thenReturn(DEVICES_TABLE);
        when(projectTransactions.execute(eq(dataSource), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<JdbcTemplate, Object> operation = invocation.getArgument(1);
            return operation.apply(jdbcTemplate);
        });
    }

    private static DeviceRegisterRequest registerRequest() {
        return new DeviceRegisterRequest(DEVICE_ID, "iPhone", "iOS 26.0", "1.0.0");
    }

    private static MultiDataSourceManager.ProjectConfig projectConfig() {
        return new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Test Project",
                "localhost",
                5432,
                "analytics_test",
                "analytics",
                "analytics_user",
                "password",
                "analytics_",
                true
        );
    }
}
