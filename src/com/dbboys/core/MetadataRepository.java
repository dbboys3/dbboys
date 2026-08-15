package com.dbboys.core;
import com.dbboys.model.Queue;

import com.dbboys.model.*;

import java.sql.DatabaseMetaData;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public interface MetadataRepository {

    List<User> getUsers(Connection conn) throws SQLException;

    default boolean supportsUsers(Connect connect) {
        return false;
    }

    List<Database> getDatabases(Connection conn) throws SQLException;

    default List<CatalogNode> getMetadataDatabases(Connection conn) throws SQLException {
        return new ArrayList<>(getDatabases(conn));
    }

    /**
     * 三层目录模型下某个库（当前连接所在库）的模式列表。
     * 仅 {@link DatabasePlatform#usesCatalogSchemaLevel()} 为 true 的方言需要实现。
     */
    default List<Schema> getSchemas(Connection conn) throws SQLException {
        return List.of();
    }

    CatalogNode getDatabaseInfo(Connection conn, String databaseName) throws SQLException;

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

    default int getObjectTypeCount(Connection conn, String databaseName) throws SQLException {
        return 0;
    }

    default List<Type> getObjectTypes(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }

    default int getQueueCount(Connection conn, String databaseName) throws SQLException {
        return 0;
    }

    default List<Queue> getQueues(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }

    default int getSchedulerJobCount(Connection conn, String databaseName) throws SQLException {
        return 0;
    }

    default List<String> getSchedulerJobNames(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }

    default int getRecycleBinCount(Connection conn) throws SQLException {
        return 0;
    }

    default List<String> getRecycleBinDisplayNames(Connection conn) throws SQLException {
        return List.of();
    }

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

    /** 表级 sqlmode（仅 Informix 族有，"Oracle"/"MySQL"/"GBase"；其余平台返回 null）。
     *  迁移的类型映射以它优先，再看连接的数据库类型。 */
    default String getTableSqlMode(Connection conn, String tableName) throws SQLException {
        return null;
    }

    // ---- 外键（数据迁移用：通用 JDBC 元数据实现，方言可覆盖提速） ----

    /** 单表外键清单：列按 KEY_SEQ 有序；库/模式按当前会话候选值探测（同主键读取）。无名外键跳过。 */
    default List<ForeignKey> getTableForeignKeys(Connection conn, String tableName) throws SQLException {
        if (conn == null || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        DatabaseMetaData metaData = conn.getMetaData();
        QualifiedName qualifiedName = parseQualifiedName(tableName);
        for (String catalog : candidateValues(conn.getCatalog())) {
            for (String schema : candidateValues(qualifiedName.schema(), conn.getSchema())) {
                List<ForeignKey> foreignKeys = readForeignKeys(metaData, catalog, schema, qualifiedName.table());
                if (!foreignKeys.isEmpty()) {
                    return foreignKeys;
                }
            }
        }
        return List.of();
    }

    /** 指定库/模式下全部表的外键（逐表读 JDBC 元数据，表多时偏慢——仅迁移展开/挑选用）。 */
    default List<ForeignKey> getForeignKeys(Connection conn, String databaseName) throws SQLException {
        List<Table> tables = getUserTables(conn, databaseName);
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<ForeignKey> result = new ArrayList<>();
        for (Table table : tables) {
            if (table != null && table.getName() != null && !table.getName().isBlank()) {
                result.addAll(getTableForeignKeys(conn, table.getName()));
            }
        }
        return result;
    }

    private static List<ForeignKey> readForeignKeys(DatabaseMetaData metaData,
                                                    String catalog,
                                                    String schema,
                                                    String tableName) throws SQLException {
        Map<String, ForeignKey> byName = new LinkedHashMap<>();
        Map<String, TreeMap<Short, String>> fkColumnsByName = new LinkedHashMap<>();
        Map<String, TreeMap<Short, String>> pkColumnsByName = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getExportedKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String fkTable = rs.getString("FKTABLE_NAME");
                String pkTable = rs.getString("PKTABLE_NAME");
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                String pkColumn = rs.getString("PKCOLUMN_NAME");
                if (fkName == null || fkName.isBlank() || fkTable == null || fkTable.isBlank()
                        || pkTable == null || pkTable.isBlank() || fkColumn == null || pkColumn == null) {
                    continue;
                }
                ForeignKey fk = byName.computeIfAbsent(fkName, n -> {
                    ForeignKey created = new ForeignKey(n);
                    created.setTableName(fkTable);
                    created.setRefTableName(pkTable);
                    created.setDeleteRule(ruleName(rsGetShortQuiet(rs, "DELETE_RULE")));
                    created.setUpdateRule(ruleName(rsGetShortQuiet(rs, "UPDATE_RULE")));
                    return created;
                });
                short keySeq = rs.getShort("KEY_SEQ");
                fkColumnsByName.computeIfAbsent(fkName, n -> new TreeMap<>()).putIfAbsent(keySeq, fkColumn);
                pkColumnsByName.computeIfAbsent(fkName, n -> new TreeMap<>()).putIfAbsent(keySeq, pkColumn);
            }
        }
        for (Map.Entry<String, ForeignKey> entry : byName.entrySet()) {
            entry.getValue().setColumns(String.join(", ", fkColumnsByName.get(entry.getKey()).values()));
            entry.getValue().setRefColumns(String.join(", ", pkColumnsByName.get(entry.getKey()).values()));
        }
        return new ArrayList<>(byName.values());
    }

    private static short rsGetShortQuiet(ResultSet rs, String column) {
        try {
            return rs.getShort(column);
        } catch (Exception e) {
            return -1;
        }
    }

    /** JDBC 规则码（importedKeyXxx）→ SQL 字面值；未知/NO ACTION 返回 null（生成 DDL 时不附加）。 */
    private static String ruleName(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            default -> null;
        };
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
