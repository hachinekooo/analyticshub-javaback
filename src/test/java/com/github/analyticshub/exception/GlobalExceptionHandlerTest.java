package com.github.analyticshub.exception;

import com.github.analyticshub.common.dto.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.context.request.WebRequest;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private static final String RAW_INPUT = "private-input-UUID-victim@example.com";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final List<String> logMessages = new ArrayList<>();
    private Logger logger;
    private Handler captureHandler;

    @BeforeEach
    void captureHandlerLogs() {
        logger = Logger.getLogger(GlobalExceptionHandler.class.getName());
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    Object[] parameters = record.getParameters();
                    logMessages.add(parameters == null || parameters.length == 0
                            ? record.getMessage()
                            : MessageFormat.format(record.getMessage(), parameters));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(captureHandler);
    }

    @AfterEach
    void stopCapturingHandlerLogs() {
        logger.removeHandler(captureHandler);
    }

    @Test
    void unreadableRequestUsesStableResponseWithoutLoggingParserMessage() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "JSON parser rejected " + RAW_INPUT,
                new MockHttpInputMessage(RAW_INPUT.getBytes(StandardCharsets.UTF_8))
        );

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleHttpMessageNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().error().message())
                .isEqualTo("数据解析失败，请检查参数格式");
        assertThat(logMessages).contains("Request body parsing failed");
        assertThat(logMessages).allMatch(message -> !message.contains(RAW_INPUT));
    }

    @Test
    void expectedExceptionLogsDoNotIncludeClientFacingMessages() {
        WebRequest webRequest = mock(WebRequest.class);

        handler.handleBusinessException(
                new BusinessException("INVALID_PROJECT", RAW_INPUT),
                webRequest
        );
        ResponseEntity<ApiResponse<Void>> illegalArgumentResponse =
                handler.handleIllegalArgumentException(new IllegalArgumentException(RAW_INPUT));

        assertThat(logMessages).contains(
                "Business exception: code=INVALID_PROJECT, status=400",
                "Illegal argument rejected"
        );
        assertThat(logMessages).allMatch(message -> !message.contains(RAW_INPUT));
        assertThat(illegalArgumentResponse.getBody()).isNotNull();
        assertThat(illegalArgumentResponse.getBody().error().message()).isEqualTo("参数无效");
        assertThat(illegalArgumentResponse.getBody().error().message()).doesNotContain(RAW_INPUT);
    }
}
