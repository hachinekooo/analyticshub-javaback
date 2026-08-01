package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.EventTrackRequest;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EventServiceBatchValidationTest {

    @Test
    void rejectsOversizedBatchBeforeOpeningProjectDatabase() {
        EventService service = new EventService(
                mock(MultiDataSourceManager.class),
                JsonMapper.builder().build(),
                mock(CounterService.class),
                new ProjectTransactionExecutor()
        );

        assertThatThrownBy(() -> service.trackEventsBatch(
                new EventTrackRequest[EventService.MAX_BATCH_SIZE + 1]
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("EVENT_BATCH_TOO_LARGE");
            assertThat(exception.getHttpStatus().value()).isEqualTo(413);
        });
    }
}
