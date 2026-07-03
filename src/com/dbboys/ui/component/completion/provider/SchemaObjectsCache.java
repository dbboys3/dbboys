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
 *
 * <p>Each entry holds a name with its {@link CachedObjectKind} so the completion
 * popup can show the correct icon badge (TABLE vs VIEW vs SYNONYM vs SYSTABLE).
 */
public final class SchemaObjectsCache {

    private static final Map<String, List<CachedObject>> CACHE = new ConcurrentHashMap<>();

    private SchemaObjectsCache() {}

    private static String key(int connectId, String databaseName) {
        return connectId + ":" + (databaseName != null ? databaseName.toLowerCase(java.util.Locale.ROOT) : "");
    }

    public static void put(int connectId, String databaseName,
                           List<String> tables, List<String> views,
                           List<String> synonyms, List<String> sysTables) {
        List<CachedObject> all = new ArrayList<>();
        if (tables != null) tables.forEach(n -> all.add(new CachedObject(n, CachedObjectKind.TABLE)));
        if (views != null) views.forEach(n -> all.add(new CachedObject(n, CachedObjectKind.VIEW)));
        if (synonyms != null) synonyms.forEach(n -> all.add(new CachedObject(n, CachedObjectKind.SYNONYM)));
        if (sysTables != null) sysTables.forEach(n -> all.add(new CachedObject(n, CachedObjectKind.SYSTABLE)));
        CACHE.put(key(connectId, databaseName), Collections.unmodifiableList(all));
    }

    public static List<CachedObject> get(int connectId, String databaseName) {
        List<CachedObject> result = CACHE.get(key(connectId, databaseName));
        return result != null ? result : List.of();
    }

    /** Clear all cached entries for a given connection (e.g. when a tab is closed). */
    public static void evict(int connectId) {
        String prefix = connectId + ":";
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ---- inner types ----

    /**
     * Kind of a cached schema object, used to pick the correct
     * {@link com.dbboys.ui.component.completion.CompletionKind} at query time.
     */
    public enum CachedObjectKind {
        TABLE, VIEW, SYNONYM, SYSTABLE
    }

    /**
     * A cached schema object name with its kind.
     */
    public static final class CachedObject {
        private final String name;
        private final CachedObjectKind kind;

        CachedObject(String name, CachedObjectKind kind) {
            this.name = name;
            this.kind = kind;
        }

        public String name() { return name; }
        public CachedObjectKind kind() { return kind; }
    }
}
