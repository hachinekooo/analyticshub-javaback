package com.github.analyticshub.controller;

import com.github.analyticshub.dto.AdminDeviceCredentialResetResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.exception.GlobalExceptionHandler;
import com.github.analyticshub.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminDeviceCredentialControllerTest {

    private static final String PROJECT_ID = "demo_project";
    private static final String DEVICE_ID = "11111111-1111-4111-8111-111111111111";

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminDeviceCredentialController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void resetReturnsTheOneTimeCredentialContractWithoutAllowingCaching() throws Exception {
        when(authService.resetCredentialsByAdmin(PROJECT_ID, DEVICE_ID)).thenReturn(
                new AdminDeviceCredentialResetResponse(
                        PROJECT_ID,
                        DEVICE_ID,
                        "ak_recovered",
                        "sk_recovered"
                )
        );

        mockMvc.perform(post(
                        "/api/admin/projects/{projectId}/devices/{deviceId}/credentials/reset",
                        PROJECT_ID,
                        DEVICE_ID
                ))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data.apiKey").value("ak_recovered"))
                .andExpect(jsonPath("$.data.secretKey").value("sk_recovered"));

        verify(authService).resetCredentialsByAdmin(PROJECT_ID, DEVICE_ID);
    }

    @Test
    void missingDeviceUsesAStableNotFoundContract() throws Exception {
        when(authService.resetCredentialsByAdmin(PROJECT_ID, DEVICE_ID))
                .thenThrow(BusinessException.deviceNotFound());

        mockMvc.perform(post(
                        "/api/admin/projects/{projectId}/devices/{deviceId}/credentials/reset",
                        PROJECT_ID,
                        DEVICE_ID
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DEVICE_NOT_FOUND"));
    }
}
