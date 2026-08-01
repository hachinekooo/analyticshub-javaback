package com.github.analyticshub.controller;

import com.github.analyticshub.dto.AdminDashboardRecord;
import com.github.analyticshub.dto.AdminDashboardUpsertRequest;
import com.github.analyticshub.exception.GlobalExceptionHandler;
import com.github.analyticshub.service.AdminDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerTest {

    @Mock
    private AdminDashboardService dashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminDashboardController(dashboardService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listAndGetExposeTheProjectScopedCamelCaseContract() throws Exception {
        AdminDashboardRecord record = record();
        when(dashboardService.list("demo_project")).thenReturn(List.of(record));
        when(dashboardService.get("demo_project", "operations")).thenReturn(record);

        mockMvc.perform(get("/api/admin/projects/demo_project/dashboards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].projectId").value("demo_project"))
                .andExpect(jsonPath("$.data[0].dashboardKey").value("operations"))
                .andExpect(jsonPath("$.data[0].schemaVersion").value(1))
                .andExpect(jsonPath("$.data[0].revision").value(3))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));

        mockMvc.perform(get("/api/admin/projects/demo_project/dashboards/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definition.widgets").isArray());
    }

    @Test
    void putBindsRevisionAndDeclarativeDefinition() throws Exception {
        when(dashboardService.upsert(eq("demo_project"), eq("operations"), any()))
                .thenReturn(record());

        mockMvc.perform(put("/api/admin/projects/demo_project/dashboards/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": {"en": "Operations"},
                                  "description": "Generic dashboard",
                                  "schemaVersion": 1,
                                  "definition": {"schemaVersion": 1, "widgets": []},
                                  "expectedRevision": 3,
                                  "isDefault": true,
                                  "isActive": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3));

        ArgumentCaptor<AdminDashboardUpsertRequest> requestCaptor =
                ArgumentCaptor.forClass(AdminDashboardUpsertRequest.class);
        verify(dashboardService).upsert(
                eq("demo_project"),
                eq("operations"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().expectedRevision()).isEqualTo(3L);
        assertThat(requestCaptor.getValue().definition().get("widgets")).isInstanceOf(List.class);
    }

    @Test
    void putRejectsIncompletePayloadBeforeCallingService() throws Exception {
        mockMvc.perform(put("/api/admin/projects/demo_project/dashboards/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": {"en": "Operations"},
                                  "schemaVersion": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(dashboardService, never()).upsert(any(), any(), any());
    }

    @Test
    void deleteRequiresAndForwardsExpectedRevision() throws Exception {
        mockMvc.perform(delete("/api/admin/projects/demo_project/dashboards/operations")
                        .queryParam("expectedRevision", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(dashboardService).delete("demo_project", "operations", 3L);

        mockMvc.perform(delete("/api/admin/projects/demo_project/dashboards/operations"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/projects/demo_project/dashboards/operations")
                        .queryParam("expectedRevision", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DASHBOARD_REVISION"));
    }

    private static AdminDashboardRecord record() {
        return new AdminDashboardRecord(
                "demo_project",
                "operations",
                Map.of("en", "Operations"),
                "Generic dashboard",
                1,
                Map.of("schemaVersion", 1, "widgets", List.of()),
                3,
                true,
                true,
                null,
                null
        );
    }
}
