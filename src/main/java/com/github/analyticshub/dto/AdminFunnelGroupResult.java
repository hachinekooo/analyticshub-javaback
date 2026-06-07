package com.github.analyticshub.dto;

import java.util.List;

public record AdminFunnelGroupResult(
        String groupValue,
        List<AdminFunnelStepResult> steps
) {}
