package com.github.analyticshub.service;

import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionTimedOutException;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminMetricsServiceTest {

    @Test
    void mapsWholeTransactionTimeoutToStableAnalyticsError() {
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        DataSource projectDataSource = mock(DataSource.class);
        when(dataSources.getProjectConfig("project")).thenReturn(new MultiDataSourceManager.ProjectConfig(
                "project", "Project", "localhost", 5432, "db", "analytics",
                "user", "password", "analytics_", true
        ));
        when(dataSources.getDataSource("project")).thenReturn(projectDataSource);
        ProjectTransactionExecutor transactions = mock(ProjectTransactionExecutor.class);
        when(transactions.executeReadOnly(any(), anyInt(), any()))
                .thenThrow(new TransactionTimedOutException("transaction deadline reached"));
        AdminMetricsService service = new AdminMetricsService(
                dataSources,
                mock(SemanticDictionaryService.class),
                new ActorIdentityResolver(),
                mock(AnalyticsPropertyFilterService.class),
                new AnalyticsQueryProperties(),
                transactions
        );

        assertThatThrownBy(() -> service.getOverview(
                "project", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_TIMEOUT");
            assertThat(exception.getHttpStatus().value()).isEqualTo(408);
        });
    }

    @Test
    void appVersionDistributionUsesTheSameInteractiveRangeBudget() {
        MultiDataSourceManager dataSources = mock(MultiDataSourceManager.class);
        AnalyticsQueryProperties queryProperties = new AnalyticsQueryProperties();
        queryProperties.setMaxRangeDays(30);
        AdminMetricsService service = new AdminMetricsService(
                dataSources,
                mock(SemanticDictionaryService.class),
                new ActorIdentityResolver(),
                mock(AnalyticsPropertyFilterService.class),
                queryProperties,
                mock(ProjectTransactionExecutor.class)
        );

        assertThatThrownBy(() -> service.getAppVersionDistribution(
                "project", "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_RANGE_EXCEEDED")
        );
    }
}
