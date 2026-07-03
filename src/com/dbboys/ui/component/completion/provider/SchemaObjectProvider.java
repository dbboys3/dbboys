package com.dbboys.ui.component.completion.provider;

import com.dbboys.model.Connect;
import com.dbboys.model.Catalog;
import com.dbboys.ui.component.completion.CandidateProvider;
import com.dbboys.ui.component.completion.CompletionContext;
import com.dbboys.ui.component.completion.CompletionItem;
import com.dbboys.ui.component.completion.CompletionKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides table/view/synonym/system-table name completions for FROM and JOIN clauses.
 *
 * <p>Reads from {@link SchemaObjectsCache}, which is populated asynchronously by
 * {@link SchemaObjectsFetcher} when the user switches database in the SQL editor.
 *
 * <p>The provider is stateful 鈥?call {@link #setContext(Connect, Catalog)} before
 * each completion query to ensure the correct cache entry is read.
 */
public class SchemaObjectProvider implements CandidateProvider {

    /** Clause-introducing keywords that the user completes before typing a table name. */
    private static final java.util.Set<String> CLAUSE_KEYWORDS = java.util.Set.of(
            "from", "join", "into", "update", "table", "view",
            "inner", "left", "right", "full", "cross", "natural");

    private int connectId;
    private String databaseName;

    public SchemaObjectProvider() {}

    /**
     * Set the active connection+database so {@link #fetch} reads from the right
     * cache partition.  Call before every completion query.
     */
    public void setContext(Connect connect, Catalog database) {
        this.connectId = (connect != null) ? connect.getId() : 0;
        this.databaseName = (database != null) ? database.getName() : null;
    }

    @Override
    public boolean appliesTo(CompletionContext ctx) {
        return !ctx.isDisabled()
                && ctx.expectsTableReferences()
                && connectId > 0
                && databaseName != null
                && !databaseName.isBlank();
    }

    @Override
    public List<CompletionItem> fetch(String prefix, CompletionContext ctx) {
        List<String> names = SchemaObjectsCache.get(connectId, databaseName);
        if (names.isEmpty()) return List.of();

        String lowerPrefix = (prefix != null) ? prefix.toLowerCase(Locale.ROOT) : "";

        // If the prefix exactly matches a completed FROM/JOIN clause keyword,
        // treat it as empty so all tables/views/synonyms are shown immediately.
        if (CLAUSE_KEYWORDS.contains(lowerPrefix)) {
            lowerPrefix = "";
        }

        List<CompletionItem> results = new ArrayList<>();
        int count = 0;
        for (String name : names) {
            if (lowerPrefix.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                CompletionKind kind = inferKind(ctx);
                results.add(new CompletionItem(name, name, kind, "", 300));
                if (++count >= MAX_RESULTS) break;
            }
        }
        return results;
    }

    private CompletionKind inferKind(CompletionContext ctx) {
        // If the context expects synonyms, mark as SYNONYM; default to TABLE
        if (ctx.getExpectedKinds().contains(CompletionKind.SYNONYM)) {
            return CompletionKind.TABLE; // we merge all under TABLE for simplicity
        }
        return CompletionKind.TABLE;
    }
}