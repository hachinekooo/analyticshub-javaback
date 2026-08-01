package com.github.analyticshub.service;

import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderOutboxSchedulerTest {

    @Test
    void disabledEmailLeavesProjectsAndOutboxesUntouched() {
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        WorkOrderOutboxDeliveryService deliveryService = mock(WorkOrderOutboxDeliveryService.class);
        EmailService emailService = mock(EmailService.class);
        when(emailService.isDeliveryEnabled()).thenReturn(false);

        scheduler(projectMapper, deliveryService, emailService).deliverPendingNotifications();

        verify(projectMapper, never()).selectList(any());
        verify(deliveryService, never()).deliverPending(any(), anyInt());
    }

    @Test
    void activeProjectsAreDeliveredAndOneFailureDoesNotStopTheRest() {
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        WorkOrderOutboxDeliveryService deliveryService = mock(WorkOrderOutboxDeliveryService.class);
        EmailService emailService = mock(EmailService.class);
        when(emailService.isDeliveryEnabled()).thenReturn(true);
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project("alpha"),
                project("beta")
        ));
        when(deliveryService.deliverPending("alpha", 25))
                .thenThrow(new IllegalStateException("project database unavailable"));

        scheduler(projectMapper, deliveryService, emailService).deliverPendingNotifications();

        verify(deliveryService, times(1)).deliverPending("alpha", 25);
        verify(deliveryService, times(1)).deliverPending("beta", 25);
    }

    private static WorkOrderOutboxScheduler scheduler(
            AnalyticsProjectMapper projectMapper,
            WorkOrderOutboxDeliveryService deliveryService,
            EmailService emailService
    ) {
        return new WorkOrderOutboxScheduler(projectMapper, deliveryService, emailService, 25);
    }

    private static AnalyticsProject project(String projectId) {
        AnalyticsProject project = new AnalyticsProject();
        project.setProjectId(projectId);
        project.setIsActive(true);
        return project;
    }
}
