package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PROJECT_ID = "test_project";
    private static final String DEVICES_TABLE = "\"analytics_devices\"";

    private AuthService authService;

    @Mock
    private MultiDataSourceManager dataSourceManager;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @BeforeEach
    void setUp() throws Exception {
        authService = new AuthService(dataSourceManager);

        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig());
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "devices")).thenReturn(DEVICES_TABLE);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    }

    @Test
    void registerDeviceRotatesCredentialsForExistingDevice() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong("id")).thenReturn(12L);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        DeviceRegisterResponse response = authService.registerDevice(PROJECT_ID, registerRequest());

        assertFalse(response.isNew());
        assertNotNull(response.apiKey());
        assertNotNull(response.secretKey());
        assertTrue(response.apiKey().startsWith("ak_"));
        assertTrue(response.secretKey().startsWith("sk_"));
        verify(connection).prepareStatement(contains("UPDATE"));
        verify(connection, never()).prepareStatement(contains("INSERT"));
    }

    @Test
    void registerDeviceInsertsNewDeviceWhenDeviceDoesNotExist() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        DeviceRegisterResponse response = authService.registerDevice(PROJECT_ID, registerRequest());

        assertTrue(response.isNew());
        assertNotNull(response.apiKey());
        assertNotNull(response.secretKey());
        verify(connection).prepareStatement(contains("INSERT"));
        verify(connection, never()).prepareStatement(contains("UPDATE"));
    }

    private static DeviceRegisterRequest registerRequest() {
        return new DeviceRegisterRequest(
                "11111111-1111-4111-8111-111111111111",
                "iPhone",
                "iOS 26.0",
                "1.0.0"
        );
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
