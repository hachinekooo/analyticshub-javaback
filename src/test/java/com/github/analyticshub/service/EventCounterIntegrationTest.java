package com.github.analyticshub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventCounterIntegrationTest {

    private EventService eventService;

    @Mock
    private MultiDataSourceManager dataSourceManager;

    @Mock
    private CounterService counterService;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private DatabaseMetaData metaData;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        eventService = new EventService(dataSourceManager, objectMapper, counterService);
        
        // Mock DB connection hierarchy for JdbcTemplate
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDriverName()).thenReturn("PostgreSQL");
        
        // Setup RequestContext
        RequestContext ctx = new RequestContext();
        Device device = new Device();
        device.setDeviceId(UUID.randomUUID());
        ctx.setProjectId("test-project");
        ctx.setDevice(device);
        ctx.setUserId("test-user");
        ctx.setDataSource(dataSource);
        RequestContext.set(ctx);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void testTrackEvent_TriggersCounter() {
        EventTrackRequest request = new EventTrackRequest(
                "send_letter", System.currentTimeMillis(), Map.of("status", "success"), null
        );
        
        when(dataSourceManager.getTableName(anyString(), eq("events"))).thenReturn("p_events");
        
        eventService.trackEvent(request);

        // Verify counterService was called
        verify(counterService, times(1)).processEventAutoIncrements(eq("test-project"), eq("send_letter"), any());
    }

    @Test
    void testTrackEventsBatch_TriggersCounter() {
        EventTrackRequest[] batch = new EventTrackRequest[]{
                new EventTrackRequest("send_letter", System.currentTimeMillis(), Map.of("status", "success"), null),
                new EventTrackRequest("open_app", System.currentTimeMillis(), null, null)
        };

        when(dataSourceManager.getTableName(anyString(), eq("events"))).thenReturn("p_events");

        eventService.trackEventsBatch(batch);

        // Verify send_letter triggered
        verify(counterService, times(1)).processEventAutoIncrements(eq("test-project"), eq("send_letter"), any());
        // Verify open_app triggered
        verify(counterService, times(1)).processEventAutoIncrements(eq("test-project"), eq("open_app"), any());
    }
}
