package com.github.analyticshub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically drains work-order notification outboxes for active projects.
 *
 * <p>The delivery service performs the actual multi-instance claim using
 * PostgreSQL {@code FOR UPDATE SKIP LOCKED}. This coordinator deliberately
 * keeps project failures isolated and skips all database work when SMTP is
 * disabled, preserving queued notifications for later delivery.</p>
 */
@Component
@ConditionalOnProperty(
        name = "app.work-order.outbox.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WorkOrderOutboxScheduler {

    private static final System.Logger log = System.getLogger(WorkOrderOutboxScheduler.class.getName());

    private final AnalyticsProjectMapper projectMapper;
    private final WorkOrderOutboxDeliveryService deliveryService;
    private final EmailService emailService;
    private final int batchSize;

    public WorkOrderOutboxScheduler(
            AnalyticsProjectMapper projectMapper,
            WorkOrderOutboxDeliveryService deliveryService,
            EmailService emailService,
            @Value("${app.work-order.outbox.scheduler-batch-size:20}") int batchSize
    ) {
        this.projectMapper = projectMapper;
        this.deliveryService = deliveryService;
        this.emailService = emailService;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${app.work-order.outbox.scheduler-initial-delay-ms:30000}",
            fixedDelayString = "${app.work-order.outbox.scheduler-fixed-delay-ms:60000}"
    )
    public void deliverPendingNotifications() {
        if (!emailService.isDeliveryEnabled()) {
            return;
        }

        List<AnalyticsProject> activeProjects;
        try {
            activeProjects = projectMapper.selectList(
                    new QueryWrapper<AnalyticsProject>()
                            .select("project_id")
                            .eq("is_active", true)
                            .orderByAsc("project_id")
            );
        } catch (RuntimeException exception) {
            log.log(System.Logger.Level.ERROR,
                    "Unable to load projects for work-order outbox delivery: failureType={0}",
                    exception.getClass().getSimpleName());
            return;
        }

        for (AnalyticsProject project : activeProjects) {
            if (project == null || project.getProjectId() == null || project.getProjectId().isBlank()) {
                continue;
            }
            try {
                deliveryService.deliverPending(project.getProjectId(), batchSize);
            } catch (RuntimeException exception) {
                log.log(System.Logger.Level.WARNING,
                        "Work-order outbox delivery skipped for projectId={0}, failureType={1}",
                        project.getProjectId(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
