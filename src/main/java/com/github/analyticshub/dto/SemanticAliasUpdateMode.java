package com.github.analyticshub.dto;

/**
 * Explicit alias mutation semantics for an idempotent definition PUT.
 */
public enum SemanticAliasUpdateMode {
    /** Replace the complete alias set, including clearing it with an empty list. */
    REPLACE,
    /** Leave the existing alias set unchanged. */
    PRESERVE
}
