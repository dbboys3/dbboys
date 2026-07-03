package com.dbboys.ui.component.completion.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for schema object names (tables, views, synonyms, system tables)
 * keyed by {@code connectId:databaseName}.
 *
 * <p>Populated by {@link SchemaObjectsFetcher} in a background thread and read by
 * {@link SchemaObjectProvider} on the FX thread during autocomplete queries.
 */
public final class SchemaObjectsCache {

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private SchemaObjectsCache() {}

    private static String key(int connectId, String databaseName) {
        return connectId + ":" + (databaseName != null ? databaseName.toLowerCase(java.util.Locale.ROOT) : "");
    }

    public static void put(int connectId, String databaseName,
                           List<String> tables, List<String> views,
                           List<String> synonyms, List<String> sysTables) {
        List<String> all = new ArrayList<>();
        if (tables != null) all.addAll(tables);
        if (views != null) all.addAll(views);
        if (synonyms != null) all.addAll(synonyms);
        if (sysTables != null) all.addAll(sysTables);
        CACHE.put(key(connectId, databaseName), Collections.unmodifiableList(all));
    }

    public static List<String> get(int connectId, String databaseName) {
        List<String> result = CACHE.get(key(connectId, databaseName));
        return result != null ? result : List.of();
    }

    /** Clear all cached entries for a given connection (e.g. when a tab is closed). */
    public static void evict(int connectId) {
        String prefix = connectId + ":";
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
