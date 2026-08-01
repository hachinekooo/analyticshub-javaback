package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import com.github.analyticshub.security.ProjectCredentialCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProjectServiceTest {

    @Mock
    private AnalyticsProjectMapper projectMapper;

    @Mock
    private MultiDataSourceManager dataSourceManager;

    @Mock
    private ProjectSchemaMigrator projectSchemaMigrator;

    @Mock
    private ProjectCredentialCipher credentialCipher;

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void runtimeCacheIsInvalidatedOnlyAfterSystemTransactionCommits() {
        AnalyticsProject project = project();
        when(projectMapper.selectById(1L)).thenReturn(project);
        AdminProjectService service = service();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.deleteProject(1L);

        verify(dataSourceManager, never()).reloadProject("project_one");
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.getFirst().afterCommit();

        verify(dataSourceManager).reloadProject("project_one");
    }

    @Test
    void directNonTransactionalUseInvalidatesImmediately() {
        AnalyticsProject project = project();
        when(projectMapper.selectById(1L)).thenReturn(project);

        service().deleteProject(1L);

        verify(dataSourceManager).reloadProject("project_one");
    }

    private AdminProjectService service() {
        return new AdminProjectService(
                projectMapper,
                dataSourceManager,
                projectSchemaMigrator,
                credentialCipher
        );
    }

    private static AnalyticsProject project() {
        AnalyticsProject project = new AnalyticsProject();
        project.setId(1L);
        project.setProjectId("project_one");
        return project;
    }
}
