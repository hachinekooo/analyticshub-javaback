package com.github.analyticshub.dto;

/**
 * Defines the lower boundary used when rebuilding an event-driven counter.
 */
public enum CounterHistoryMode {
    /** Count every matching event already stored for the project. */
    INCLUDE_EXISTING,

    /** Count only events ingested after this mode was first selected. */
    START_FROM_NOW
}
