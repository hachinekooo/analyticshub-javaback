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
 * 协调各活跃项目的旧事件属性元数据回填。
 *
 * <p>调度层只负责枚举与故障隔离；行领取、批次上限和事务边界由回填服务负责。</p>
 */
@Component
@ConditionalOnProperty(
        name = "app.analytics.event-property-backfill.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EventPropertyMetadataBackfillScheduler {

    private static final System.Logger log =
            System.getLogger(EventPropertyMetadataBackfillScheduler.class.getName());

    private final AnalyticsProjectMapper projectMapper;
    private final EventPropertyMetadataBackfillService backfillService;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public EventPropertyMetadataBackfillScheduler(
            AnalyticsProjectMapper projectMapper,
            EventPropertyMetadataBackfillService backfillService,
            @Value("${app.analytics.event-property-backfill.batch-size:20}") int batchSize,
            @Value("${app.analytics.event-property-backfill.max-batches-per-run:10}") int maxBatchesPerRun
    ) {
        this.projectMapper = projectMapper;
        this.backfillService = backfillService;
        this.batchSize = Math.max(1, Math.min(batchSize, EventPropertyMetadataBackfillService.MAX_BATCH_SIZE));
        this.maxBatchesPerRun = Math.max(1, Math.min(maxBatchesPerRun, 20));
    }

    @Scheduled(
            initialDelayString = "${app.analytics.event-property-backfill.initial-delay-ms:1000}",
            fixedDelayString = "${app.analytics.event-property-backfill.fixed-delay-ms:5000}"
    )
    public void backfillActiveProjects() {
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
                    "Unable to load projects for event metadata backfill: failureType={0}",
                    exception.getClass().getSimpleName());
            return;
        }

        for (AnalyticsProject project : activeProjects) {
            if (project == null || project.getProjectId() == null || project.getProjectId().isBlank()) {
                continue;
            }
            try {
                for (int batch = 0; batch < maxBatchesPerRun; batch++) {
                    int processed = backfillService.backfillNextBatch(project.getProjectId(), batchSize);
                    if (processed < batchSize) {
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                log.log(System.Logger.Level.WARNING,
                        "Event metadata backfill skipped for projectId={0}, failureType={1}",
                        project.getProjectId(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
