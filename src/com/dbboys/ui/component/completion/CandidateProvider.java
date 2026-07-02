package com.dbboys.ui.component.completion;

import java.util.List;

/**
 * A pluggable source of completion candidates.
 * Each provider decides whether it applies to a given context and returns
 * up to {@value #MAX_RESULTS} candidates matching the prefix.
 */
public interface CandidateProvider {

    /** Hard cap per provider to keep the popup scannable. */
    int MAX_RESULTS = 30;

    /**
     * Whether this provider should be queried for the given context.
     */
    boolean appliesTo(CompletionContext ctx);

    /**
     * Return candidates whose label starts with {@code prefix} (case-insensitive).
     * Must never return null — use {@link List#of()} for "no results".
     */
    List<CompletionItem> fetch(String prefix, CompletionContext ctx);
}
