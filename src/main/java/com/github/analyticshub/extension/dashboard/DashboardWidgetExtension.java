package com.github.analyticshub.extension.dashboard;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Build-time extension contract for a trusted project-specific dashboard widget.
 *
 * <p>Downstream deployments register an implementation as a Spring bean. The
 * open-source validator then recognizes the widget type and still rejects every
 * config field that the extension did not explicitly allow. Implementations
 * must validate the value and shape of every extension-specific field.</p>
 */
public interface DashboardWidgetExtension {

    /**
     * Stable widget type. Extension types must use the {@code custom.} namespace.
     */
    String type();

    /**
     * Explicit top-level config field allow-list. The common {@code title}
     * field is supplied by the base and must not be repeated here.
     */
    Set<String> allowedConfigFields();

    /**
     * Whether the widget requires a config object.
     */
    default boolean configRequired() {
        return false;
    }

    /**
     * Validate extension-specific config values. Throw
     * {@link IllegalArgumentException} with a safe operator-facing message when
     * the config is invalid.
     */
    void validateConfig(JsonNode config);
}
