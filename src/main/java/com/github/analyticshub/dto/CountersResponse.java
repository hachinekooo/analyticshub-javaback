package com.github.analyticshub.dto;

import java.util.List;

public record CountersResponse(
        String projectId,
        List<CounterRecord> items
) {}
