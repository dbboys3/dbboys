package com.dbboys.ui.component.completion;

/**
 * Types of completion candidates, used for icon/grouping display and provider
 * applicability filtering.
 */
public enum CompletionKind {
    KEYWORD,
    TABLE,
    VIEW,
    COLUMN,
    FUNCTION,
    SCHEMA,
    ALIAS,
    SNIPPET,
    SYNONYM
}
