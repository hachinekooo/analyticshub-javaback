package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.dto.PublicCounterResponse;
import com.github.analyticshub.service.CounterService;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/counters")
public class PublicCounterController {

    private static final List<Locale.LanguageRange> DEFAULT_LANGUAGE_RANGES =
            List.of(new Locale.LanguageRange("zh"));
    private static final List<Locale.LanguageRange> ENGLISH_LANGUAGE_RANGES =
            List.of(new Locale.LanguageRange("en"));

    private final CounterService counterService;

    public PublicCounterController(CounterService counterService) {
        this.counterService = counterService;
    }

    @GetMapping
    public ApiResponse<List<PublicCounterResponse>> list(@RequestParam("projectId") String projectId,
                                                       HttpServletRequest request) {
        List<Locale.LanguageRange> languages = resolveLanguages(request);
        CountersResponse raw = counterService.list(projectId, true);
        
        List<PublicCounterResponse> items = raw.items().stream()
                .map(item -> localize(item, languages))
                .collect(Collectors.toList());
                
        return ApiResponse.success(items);
    }

    @GetMapping("/{key}")
    public ApiResponse<PublicCounterResponse> get(@RequestParam("projectId") String projectId,
                                                @PathVariable("key") String key,
                                                HttpServletRequest request) {
        List<Locale.LanguageRange> languages = resolveLanguages(request);
        CounterRecord item = counterService.get(projectId, key, true);
        if (item == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(localize(item, languages));
    }

    private List<Locale.LanguageRange> resolveLanguages(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LANGUAGE_RANGES;
        }
        try {
            return Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_LANGUAGE_RANGES;
        }
    }

    private PublicCounterResponse localize(CounterRecord item, List<Locale.LanguageRange> languages) {
        return new PublicCounterResponse(
                item.key(),
                item.value(),
                getText(item.displayName(), languages),
                getText(item.unit(), languages),
                item.updatedAt()
        );
    }

    private String getText(JsonNode node, List<Locale.LanguageRange> languages) {
        if (node == null || node.isNull()) return "";
        if (node.isString()) return node.asString();

        List<String> availableTags = node.propertyNames().stream()
                .filter(PublicCounterController::isWellFormedLanguageTag)
                .toList();
        String matchedTag = findMatchingTag(languages, availableTags);
        if (matchedTag == null) {
            matchedTag = findMatchingTag(DEFAULT_LANGUAGE_RANGES, availableTags);
        }
        if (matchedTag == null) {
            matchedTag = findMatchingTag(ENGLISH_LANGUAGE_RANGES, availableTags);
        }
        if (matchedTag != null) {
            return asString(node.get(matchedTag));
        }

        var properties = node.properties();
        return properties.isEmpty() ? "" : asString(properties.iterator().next().getValue());
    }

    private static String findMatchingTag(List<Locale.LanguageRange> languages, List<String> availableTags) {
        for (Locale.LanguageRange language : languages) {
            if (language.getWeight() <= 0 || language.getRange().contains("*")) {
                continue;
            }

            String match = Locale.lookupTag(List.of(language), availableTags);
            if (match != null) {
                return match;
            }

            String baseLanguage = Locale.forLanguageTag(language.getRange()).getLanguage();
            if (!baseLanguage.isBlank()) {
                match = availableTags.stream()
                        .filter(tag -> Locale.forLanguageTag(tag).getLanguage().equalsIgnoreCase(baseLanguage))
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static boolean isWellFormedLanguageTag(String tag) {
        try {
            new Locale.Builder().setLanguageTag(tag).build();
            return true;
        } catch (IllformedLocaleException ignored) {
            return false;
        }
    }

    private static String asString(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString("");
    }
}
