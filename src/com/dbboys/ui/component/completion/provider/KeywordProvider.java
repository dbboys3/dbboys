package com.dbboys.ui.component.completion.provider;

import com.dbboys.infra.util.KeywordsHighlightUtil;
import com.dbboys.ui.component.completion.CandidateProvider;
import com.dbboys.ui.component.completion.CompletionContext;
import com.dbboys.ui.component.completion.CompletionItem;
import com.dbboys.ui.component.completion.CompletionKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides SQL keyword completions from the static keyword list in
 * {@link KeywordsHighlightUtil}.
 *
 * <p>Always applicable except when the context is explicitly disabled.
 * Runs entirely in-memory 鈥?zero I/O.
 */
public class KeywordProvider implements CandidateProvider {

    private static final CompletionItem[] ALL_KEYWORDS = loadKeywords();

    private static CompletionItem[] loadKeywords() {
        String[] keywords = KeywordsHighlightUtil.getSqlKeywords();
        CompletionItem[] items = new CompletionItem[keywords.length];
        for (int i = 0; i < keywords.length; i++) {
            items[i] = new CompletionItem(
                    keywords[i],
                    keywords[i] + " ",
                    CompletionKind.KEYWORD,
                    "keyword",
                    100
            );
        }
        return items;
    }

    @Override
    public boolean appliesTo(CompletionContext ctx) {
        return !ctx.isDisabled()
                && ctx.getExpectedKinds().contains(CompletionKind.KEYWORD);
    }

    @Override
    public List<CompletionItem> fetch(String prefix, CompletionContext ctx) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<CompletionItem> results = new ArrayList<>();
        int count = 0;
        for (CompletionItem item : ALL_KEYWORDS) {
            if (item.getLabel().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {

                // Don't suggest a keyword the user has already fully typed
                if (item.getLabel().equalsIgnoreCase(prefix)) continue;
                results.add(item);
                if (++count >= MAX_RESULTS) break;
            }
        }
        return results;
    }
}