package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.EventCatalogResponse;
import com.github.analyticshub.dto.SemanticDefinitionResponse;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticDefinitionsResponse;
import com.github.analyticshub.dto.SemanticDeleteResponse;
import com.github.analyticshub.service.SemanticDictionaryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/projects/{projectId}")
public class AdminSemanticController {

    private final SemanticDictionaryService semanticDictionaryService;

    public AdminSemanticController(SemanticDictionaryService semanticDictionaryService) {
        this.semanticDictionaryService = semanticDictionaryService;
    }

    @GetMapping("/event-catalog")
    public ApiResponse<EventCatalogResponse> eventCatalog(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(semanticDictionaryService.getEventCatalog(projectId, sourceKind));
    }

    @GetMapping("/semantics")
    public ApiResponse<SemanticDefinitionsResponse> listDefinitions(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(semanticDictionaryService.listDefinitions(projectId, sourceKind));
    }

    @GetMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDefinitionResponse> getDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(
                semanticDictionaryService.getDefinition(projectId, sourceKind, semanticKey)
        );
    }

    @PutMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDefinitionResponse> upsertDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @Valid @RequestBody SemanticDefinitionUpsertRequest request
    ) {
        return ApiResponse.success(
                semanticDictionaryService.upsertDefinition(projectId, semanticKey, request)
        );
    }

    @DeleteMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDeleteResponse> deleteDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(
                semanticDictionaryService.deleteDefinition(projectId, sourceKind, semanticKey)
        );
    }
}
