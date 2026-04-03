package com.dbboys.api;

import com.dbboys.vo.*;

import java.sql.DatabaseMetaData;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

public interface MetadataRepository {

    List<User> getUsers(Connection conn) throws SQLException;

    default boolean supportsUsers(Connect connect) {
        return false;
    }

    List<Database> getDatabases(Connection conn) throws SQLException;

    default List<Database> getMetadataDatabases(Connection conn) throws SQLException {
        return getDatabases(conn);
    }

    Database getDatabaseInfo(Connection conn, String databaseName) throws SQLException;

    int getUserTablesCount(Connection conn) throws SQLException;

    String getUserTablesSize(Connection conn, String databaseName) throws SQLException;

    int getSystemTablesCount(Connection conn) throws SQLException;

    String getSystemTablesSize(Connection conn, String databaseName) throws SQLException;

    List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException;

    List<Table> getUserTables(Connection conn, String databaseName) throws SQLException;

    Table getTable(Connection conn, String databaseName, String tableName) throws SQLException;

    String getTableComment(Connection conn, String tableName) throws SQLException;

    ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException;

    List<Index> getIndexes(Connection conn, String databaseName) throws SQLException;

    int getIndexCount(Connection conn) throws SQLException;

    String getIndexSize(Connection conn) throws SQLException;

    Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException;

    int getSequenceCount(Connection conn) throws SQLException;

    List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException;

    int getSynonymCount(Connection conn) throws SQLException;

    List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException;

    int getTriggerCount(Connection conn) throws SQLException;

    List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException;

    Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException;

    int getViewCount(Connection conn) throws SQLException;

    List<View> getViews(Connection conn, String databaseName) throws SQLException;

    int getSystemDualTabId(Connection conn) throws SQLException;

    boolean hasSysProcTypeColumn(Connection conn) throws SQLException;

    int getFunctionCount(Connection conn, boolean filterType) throws SQLException;

    List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException;

    int getProcedureCount(Connection conn, boolean filterType) throws SQLException;

    List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException;

    int getPackageCount(Connection conn) throws SQLException;

    List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException;

    List<String> getStorageSpacesForCreateDatabase(Connection conn) throws SQLException;

    void changeDatabase(Connection conn, String databaseName) throws SQLException;

    void setDatabase(Connection conn, String databaseName) throws SQLException;

    List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException;

    default List<String> getPrimaryKeyColumns(Connection conn, String tableName) throws SQLException {
        if (conn == null || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        DatabaseMetaData metaData = conn.getMetaData();
        QualifiedName qualifiedName = parseQualifiedName(tableName);
        return lookupPrimaryKeyColumns(metaData, conn, qualifiedName);
    }

    default List<String> getTableColumnNames(Connection conn, String tableName) throws SQLException {
        if (conn == null || tableName == null || tableName.isBlank()) {
            return List.of();
        }

        QualifiedName qualifiedName = parseQualifiedName(tableName);
        try {
            ArrayList<ColumnsInfo> columns = getColumns(conn, qualifiedName.table());
            if (columns != null && !columns.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (ColumnsInfo column : columns) {
                    String normalized = normalizeIdentifier(column.getColName());
                    if (!normalized.isEmpty()) {
                        names.add(normalized);
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // fall back to JDBC metadata
        }

        DatabaseMetaData metaData = conn.getMetaData();
        return lookupTableColumnNames(metaData, conn, qualifiedName);
    }

    private static List<String> lookupPrimaryKeyColumns(DatabaseMetaData metaData,
                                                        Connection conn,
                                                        QualifiedName qualifiedName) throws SQLException {
        for (String catalog : candidateValues(conn.getCatalog())) {
            for (String schema : candidateValues(qualifiedName.schema(), conn.getSchema())) {
                for (String table : candidateValues(qualifiedName.table())) {
                    List<String> columns = readPrimaryKeys(metaData, catalog, schema, table);
                    if (!columns.isEmpty()) {
                        return columns;
                    }
                }
            }
        }
        return List.of();
    }

    private static List<String> lookupTableColumnNames(DatabaseMetaData metaData,
                                                       Connection conn,
                                                       QualifiedName qualifiedName) throws SQLException {
        for (String catalog : candidateValues(conn.getCatalog())) {
            for (String schema : candidateValues(qualifiedName.schema(), conn.getSchema())) {
                for (String table : candidateValues(qualifiedName.table())) {
                    List<String> columns = readColumnNames(metaData, catalog, schema, table);
                    if (!columns.isEmpty()) {
                        return columns;
                    }
                }
            }
        }
        return List.of();
    }

    private static List<String> readPrimaryKeys(DatabaseMetaData metaData,
                                                String catalog,
                                                String schema,
                                                String tableName) throws SQLException {
        TreeMap<Short, String> ordered = new TreeMap<>();
        List<String> unordered = new ArrayList<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                String columnName = normalizeIdentifier(rs.getString("COLUMN_NAME"));
                if (columnName.isEmpty()) {
                    continue;
                }
                short keySeq = rs.getShort("KEY_SEQ");
                if (keySeq > 0) {
                    ordered.putIfAbsent(keySeq, columnName);
                } else if (!unordered.contains(columnName)) {
                    unordered.add(columnName);
                }
            }
        }
        List<String> result = new ArrayList<>(ordered.values());
        for (String column : unordered) {
            if (!result.contains(column)) {
                result.add(column);
            }
        }
        return result;
    }

    private static List<String> readColumnNames(DatabaseMetaData metaData,
                                                String catalog,
                                                String schema,
                                                String tableName) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, null)) {
            while (rs.next()) {
                String columnName = normalizeIdentifier(rs.getString("COLUMN_NAME"));
                if (!columnName.isEmpty()) {
                    result.add(columnName);
                }
            }
        }
        return result;
    }

    private static List<String> candidateValues(String... values) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(null);
        if (values == null) {
            return new ArrayList<>(candidates);
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            candidates.add(value);
            candidates.add(value.toUpperCase(Locale.ROOT));
            candidates.add(value.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(candidates);
    }

    private static QualifiedName parseQualifiedName(String tableName) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < tableName.length(); i++) {
            char ch = tableName.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                part.append(ch);
            } else if (ch == '.' && !inQuotes) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(ch);
            }
        }
        if (part.length() > 0) {
            parts.add(part.toString());
        }

        String table = parts.isEmpty() ? tableName : parts.get(parts.size() - 1);
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        return new QualifiedName(normalizeIdentifier(schema), normalizeIdentifier(table));
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String normalized = identifier.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("`") && normalized.endsWith("`") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    record QualifiedName(String schema, String table) {
    }
}
