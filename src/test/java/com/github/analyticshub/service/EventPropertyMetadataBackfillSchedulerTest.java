package com.github.analyticshub.service;

import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventPropertyMetadataBackfillSchedulerTest {

    @Test
    void activeProjectsAreProcessedAndOneFailureDoesNotStopTheRest() {
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        EventPropertyMetadataBackfillService backfillService = mock(EventPropertyMetadataBackfillService.class);
        when(projectMapper.selectList(any())).thenReturn(List.of(project("alpha"), project("beta")));
        when(backfillService.backfillNextBatch("alpha", 25))
                .thenThrow(new IllegalStateException("project database unavailable"));
        new EventPropertyMetadataBackfillScheduler(projectMapper, backfillService, 25, 10)
                .backfillActiveProjects();

        verify(backfillService, times(1)).backfillNextBatch("alpha", 25);
        verify(backfillService, times(1)).backfillNextBatch("beta", 25);
    }

    @Test
    void oneRunCatchesUpSeveralBatchesButStopsAsSoonAsTheTailIsReached() {
        AnalyticsProjectMapper projectMapper = mock(AnalyticsProjectMapper.class);
        EventPropertyMetadataBackfillService backfillService = mock(EventPropertyMetadataBackfillService.class);
        when(projectMapper.selectList(any())).thenReturn(List.of(project("alpha")));
        when(backfillService.backfillNextBatch("alpha", 20)).thenReturn(20, 20, 7);

        new EventPropertyMetadataBackfillScheduler(projectMapper, backfillService, 20, 10)
                .backfillActiveProjects();

        verify(backfillService, times(3)).backfillNextBatch("alpha", 20);
    }

    private static AnalyticsProject project(String projectId) {
        AnalyticsProject project = new AnalyticsProject();
        project.setProjectId(projectId);
        project.setIsActive(true);
        return project;
    }
}
