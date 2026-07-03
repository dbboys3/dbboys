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
 * <p>The provider is stateful — call {@link #setContext(Connect, Catalog)} before
 * each completion query to ensure the correct cache entry is read.
 */
public class SchemaObjectProvider implements CandidateProvider {

    /** Higher cap for schema objects — there can be thousands of tables. */
    private static final int SCHEMA_MAX_RESULTS = 200;

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
        List<SchemaObjectsCache.CachedObject> objects = SchemaObjectsCache.get(connectId, databaseName);
        if (objects.isEmpty()) return List.of();

        String lowerPrefix = (prefix != null) ? prefix.toLowerCase(Locale.ROOT) : "";

        // If the prefix ends with a space, the caret is after a clause keyword + space.
        // Clear the prefix so all tables/views/synonyms are shown immediately.
        // This avoids showing tables while the user is still typing the keyword itself.
        if (lowerPrefix.endsWith(" ") && !lowerPrefix.endsWith("  ")) {
            String trimmed = lowerPrefix.trim();
            if (CLAUSE_KEYWORDS.contains(trimmed)) {
                lowerPrefix = "";
            }
        }

        List<CompletionItem> results = new ArrayList<>();
        int count = 0;
        for (SchemaObjectsCache.CachedObject obj : objects) {
            if (lowerPrefix.isEmpty() || obj.name().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                results.add(new CompletionItem(obj.name(), obj.name(), mapKind(obj), "", priority(obj)));
                if (++count >= SCHEMA_MAX_RESULTS) break;
            }
        }
        return results;
    }

    private static CompletionKind mapKind(SchemaObjectsCache.CachedObject obj) {
        return switch (obj.kind()) {
            case TABLE    -> CompletionKind.TABLE;
            case VIEW     -> CompletionKind.VIEW;
            case SYNONYM  -> CompletionKind.SYNONYM;
            case SYSTABLE -> CompletionKind.SYSTABLE;
        };
    }

    private static int priority(SchemaObjectsCache.CachedObject obj) {
        return switch (obj.kind()) {
            case TABLE    -> 100;
            case VIEW     -> 200;
            case SYNONYM  -> 300;
            case SYSTABLE -> 400;
        };
    }
}
