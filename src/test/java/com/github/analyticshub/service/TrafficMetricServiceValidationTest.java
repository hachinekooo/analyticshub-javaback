package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.TrafficMetricTrackRequest;
import com.github.analyticshub.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TrafficMetricServiceValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void requestLengthsMatchProjectDatabaseContract() {
        TrafficMetricTrackRequest request = new TrafficMetricTrackRequest(
                "x".repeat(51),
                "p".repeat(256),
                "r".repeat(256),
                null,
                null,
                null
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("metricType", "pagePath", "referrer");
    }

    @Test
    void oversizedBatchIsRejectedBeforeProjectDatabaseAccess() {
        MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
        TrafficMetricService service = new TrafficMetricService(
                dataSourceManager,
                JsonMapper.builder().build(),
                ""
        );
        TrafficMetricTrackRequest[] items =
                new TrafficMetricTrackRequest[TrafficMetricService.MAX_BATCH_SIZE + 1];

        assertThatThrownBy(() -> service.trackBatch(items, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("TRAFFIC_BATCH_TOO_LARGE");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(400);
                });
        verifyNoInteractions(dataSourceManager);
    }
}
