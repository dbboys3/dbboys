package com.dbboys.ui.component.completion;

import com.dbboys.model.Catalog;
import com.dbboys.model.Connect;
import com.dbboys.ui.component.completion.provider.FunctionProvider;
import com.dbboys.ui.component.completion.provider.KeywordProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Central orchestration point for SQL completion.
 *
 * <p>Owns the chain of {@link CandidateProvider}s and the {@link SqlContextAnalyzer}.
 * Queries are synchronous and return within a few ms — providers that need I/O
 * (Phase 2+) return cached results synchronously and trigger background loads
 * for subsequent queries.
 */
public class CompletionEngine {

    private final SqlContextAnalyzer contextAnalyzer = new SqlContextAnalyzer();
    private final List<CandidateProvider> providers = new ArrayList<>();

    public CompletionEngine() {
        // L0 providers — always available, zero I/O
        registerProvider(new KeywordProvider());
        registerProvider(new FunctionProvider());
        // Phase 2+ providers registered later
    }

    /** Register an additional provider (called during wiring or Phase 2+ setup). */
    public void registerProvider(CandidateProvider provider) {
        providers.add(provider);
    }

    /**
     * Main entry point.  Returns a sorted, deduplicated result set ready for display.
     *
     * @param fullSql  complete editor text
     * @param caretPos absolute caret offset
     * @param connect  active connection (may be default/null — Phase 1 ignores it)
     * @param database active database (may be default/null — Phase 1 ignores it)
     */
    public CompletionResult complete(String fullSql, int caretPos,
                                     Connect connect, Catalog database) {
        CompletionContext ctx = contextAnalyzer.analyze(fullSql, caretPos);
        if (ctx.isDisabled()) {
            return CompletionResult.disabled(ctx);
        }

        String prefix = ctx.getPrefixToken();

        List<CompletionItem> results = new ArrayList<>();
        for (CandidateProvider provider : providers) {
            if (provider.appliesTo(ctx)) {
                List<CompletionItem> batch = provider.fetch(prefix, ctx);
                results.addAll(batch);
            }
        }

        sortAndDeduplicate(results);
        return new CompletionResult(ctx, results);
    }

    private void sortAndDeduplicate(List<CompletionItem> items) {
        // Remove duplicates (by label, case-insensitive)
        List<CompletionItem> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CompletionItem item : items) {
            String key = item.getLabel().toLowerCase(Locale.ROOT) + ":" + item.getKind().name();
            if (seen.add(key)) {
                deduped.add(item);
            }
        }
        // Sort: priority asc, then label asc
        Collections.sort(deduped);
        items.clear();
        items.addAll(deduped);
    }

    // ---- result type ----

    /**
     * Bundles the context with the results so the popup can show contextual
     * information (e.g. "no results for ..." in the footer).
     */
    public static class CompletionResult {

        private static final CompletionResult DISABLED = new CompletionResult(
                CompletionContext.disabled().setCaretPosition(0).build(),
                List.of()
        );
        private static final CompletionResult EMPTY = new CompletionResult(
                CompletionContext.enabled("", 0).build(),
                List.of()
        );

        private final CompletionContext context;
        private final List<CompletionItem> items;

        private CompletionResult(CompletionContext context, List<CompletionItem> items) {
            this.context = context;
            this.items = items;
        }

        static CompletionResult disabled(CompletionContext ctx) {
            return new CompletionResult(ctx, List.of());
        }

        public CompletionContext getContext() {
            return context;
        }

        public List<CompletionItem> getItems() {
            return items;
        }

        public boolean shouldShow() {
            return items.size() >= 1;
        }

        /** True when the prefix is at least 2 characters — used to gate auto-popup. */
        public boolean minPrefixMet() {
            return context.getPrefixToken().length() >= 2;
        }

        public int size() {
            return items.size();
        }
    }
}
