package com.dbboys.ui.component.completion.provider;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppExecutor;
import com.dbboys.core.ConnectionService;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.MetadataRepository;
import com.dbboys.model.Connect;
import com.dbboys.model.Catalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fetches schema object names (tables, views, synonyms, system tables) for the
 * current connection + database combination in a background thread.
 *
 * <p>Creates a <em>new</em> JDBC connection for the fetch so it never interferes
 * with the main SQL editor connection.  The connection is closed immediately
 * after the fetch completes.
 *
 * <p>Results are stored in {@link SchemaObjectsCache} and consumed by
 * {@link SchemaObjectProvider} during FROM/JOIN autocomplete.
 */
public final class SchemaObjectsFetcher {

    private static final Logger log = LogManager.getLogger(SchemaObjectsFetcher.class);

    private SchemaObjectsFetcher() {}

    /**
     * Launch a background fetch for schema objects on the given connect+database.
     * <p>
     * Safe to call from any thread.  The actual work runs on {@link AppExecutor}'s
     * thread pool.
     */
    public static void fetchAsync(Connect connect, Catalog database) {
        if (connect == null || connect == databaseGetDefault(connect)) return;
        if (database == null || database.getName() == null || database.getName().isBlank()) return;

        // Snapshot connect info for the background thread — Connect is mutable on the FX thread
        Connect snapshot = new Connect(connect);
        String dbName = database.getName();

        AppExecutor.runAsync(() -> fetchAndCache(snapshot, dbName));
    }

    private static boolean databaseGetDefault(Connect connect) {
        // The default placeholder Connect has name like "请选择连接" — skip it
        return connect.getId() <= 0;
    }

    private static void fetchAndCache(Connect connect, String databaseName) {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        DatabasePlatformResolver resolver = DatabasePlatformResolver.getInstance();
        MetadataRepository repo = resolver.metadata(connect);

        Connection conn = null;
        try {
            conn = connectionService.createConnection(connect);
            if (conn == null) {
                log.warn("SchemaObjectsFetcher: failed to create connection for {}", connect.getName());
                return;
            }

            // Switch to the target database
            repo.setDatabase(conn, databaseName);

            List<String> tables = extractNames(repo.getUserTables(conn, databaseName));
            log.debug("SchemaObjectsFetcher: {} tables from {}/{}", tables.size(), connect.getName(), databaseName);

            List<String> views = extractNames(repo.getViews(conn, databaseName));
            log.debug("SchemaObjectsFetcher: {} views from {}/{}", views.size(), connect.getName(), databaseName);

            List<String> synonyms = extractNames(repo.getSynonyms(conn, databaseName));
            log.debug("SchemaObjectsFetcher: {} synonyms from {}/{}", synonyms.size(), connect.getName(), databaseName);

            List<String> sysTables = extractNames(repo.getSystemTables(conn, databaseName));
            log.debug("SchemaObjectsFetcher: {} sysTables from {}/{}", sysTables.size(), connect.getName(), databaseName);

            SchemaObjectsCache.put(connect.getId(), databaseName, tables, views, synonyms, sysTables);
            log.info("SchemaObjectsFetcher: cached {} total objects for {}/{}",
                    tables.size() + views.size() + synonyms.size() + sysTables.size(),
                    connect.getName(), databaseName);

        } catch (Exception e) {
            log.warn("SchemaObjectsFetcher: failed to fetch schema objects for {}/{}: {}",
                    connect.getName(), databaseName, e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.debug("SchemaObjectsFetcher: error closing fetch connection", e);
                }
            }
        }
    }

    private static List<String> extractNames(List<? extends com.dbboys.model.TreeData> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(items.size());
        for (com.dbboys.model.TreeData item : items) {
            String name = item.getName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
