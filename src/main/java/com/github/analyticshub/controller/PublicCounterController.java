package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.dto.PublicCounterResponse;
import com.github.analyticshub.service.CounterService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/counters")
public class PublicCounterController {

    private final CounterService counterService;

    public PublicCounterController(CounterService counterService) {
        this.counterService = counterService;
    }

    @GetMapping
    public ApiResponse<List<PublicCounterResponse>> list(@RequestParam("projectId") String projectId,
                                                       HttpServletRequest request) {
        String lang = resolveLang(request);
        CountersResponse raw = counterService.list(projectId, true);
        
        List<PublicCounterResponse> items = raw.items().stream()
                .map(item -> localize(item, lang))
                .collect(Collectors.toList());
                
        return ApiResponse.success(items);
    }

    @GetMapping("/{key}")
    public ApiResponse<PublicCounterResponse> get(@RequestParam("projectId") String projectId,
                                                @PathVariable("key") String key,
                                                HttpServletRequest request) {
        String lang = resolveLang(request);
        CounterRecord item = counterService.get(projectId, key, true);
        if (item == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(localize(item, lang));
    }

    private String resolveLang(HttpServletRequest request) {
        String acceptLang = request.getHeader("Accept-Language");
        if (acceptLang == null || acceptLang.isBlank()) return "zh";
        return acceptLang.split(",")[0].split("-")[0].toLowerCase();
    }

    private PublicCounterResponse localize(CounterRecord item, String lang) {
        return new PublicCounterResponse(
                item.key(),
                item.value(),
                getText(item.displayName(), lang),
                getText(item.unit(), lang),
                item.updatedAt()
        );
    }

    private String getText(JsonNode node, String lang) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        
        if (node.has(lang)) return node.get(lang).asText();
        if (node.has("zh")) return node.get("zh").asText();
        if (node.has("en")) return node.get("en").asText();
        
        return node.fields().hasNext() ? node.fields().next().getValue().asText() : "";
    }
}
