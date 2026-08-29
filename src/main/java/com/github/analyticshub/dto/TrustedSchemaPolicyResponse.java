package com.github.analyticshub.dto;

import java.util.List;

/** 项目级可信事件协议边界；具体属性和值由项目配置，不由平台写死。 */
public record TrustedSchemaPolicyResponse(
        String projectId,
        String propertyKey,
        List<String> trustedValues
) {}
