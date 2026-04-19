package com.dbboys.impl.dialect.genericjdbc;

import com.dbboys.api.MetadataRepository;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.Function;
import com.dbboys.vo.Index;
import com.dbboys.vo.Procedure;
import com.dbboys.vo.Sequence;
import com.dbboys.vo.Synonym;
import com.dbboys.vo.SysTable;
import com.dbboys.vo.Table;
import com.dbboys.vo.Trigger;
import com.dbboys.vo.User;
import com.dbboys.vo.View;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GeneralJdbcMetadataRepository implements MetadataRepository {

    @Override
    public List<User> getUsers(Connection conn) {
        return List.of();
    }

    @Override
    public List<Catalog> getDatabases(Connection conn) throws SQLException {
        LinkedHashMap<String, Catalog> catalogs = new LinkedHashMap<>();
        if (conn == null) {
            return List.of();
        }
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getCatalogs()) {
            while (rs.next()) {
                String name = trimToEmpty(rs.getString(1));
                if (!name.isEmpty()) {
                    catalogs.putIfAbsent(name.toLowerCase(Locale.ROOT), createCatalog(name));
                }
            }
        } catch (SQLException ignored) {
            // fall through
        }
        if (catalogs.isEmpty()) {
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    String schema = trimToEmpty(rs.getString("TABLE_SCHEM"));
                    if (!schema.isEmpty()) {
                        catalogs.putIfAbsent(schema.toLowerCase(Locale.ROOT), createCatalog(schema));
                    }
                }
            } catch (SQLException ignored) {
                // fall through
            }
        }
        if (catalogs.isEmpty()) {
            String currentCatalog = trimToEmpty(conn.getCatalog());
            if (currentCatalog.isEmpty()) {
                try {
                    currentCatalog = trimToEmpty(conn.getSchema());
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (currentCatalog.isEmpty()) {
                currentCatalog = "DEFAULT";
            }
            catalogs.put(currentCatalog.toLowerCase(Locale.ROOT), createCatalog(currentCatalog));
        }
        return new ArrayList<>(catalogs.values());
    }

    @Override
    public Catalog getDatabaseInfo(Connection conn, String databaseName) {
        return createCatalog(blankToFallback(databaseName, "DEFAULT"));
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        return countTables(conn, "TABLE");
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) {
        return "";
    }

    @Override
    public int getSystemTablesCount(Connection conn) {
        return 0;
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) {
        return "";
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        ArrayList<Table> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Scope scope = resolveScope(conn, databaseName);
        try (ResultSet rs = metaData.getTables(scope.catalog(), scope.schemaPattern(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                Table table = new Table(trimToEmpty(rs.getString("TABLE_NAME")));
                table.setTableCatalog(firstNonBlank(rs.getString("TABLE_CAT"), scope.catalog(), scope.schemaDisplay()));
                table.setTableOwner(firstNonBlank(rs.getString("TABLE_SCHEM"), scope.schemaDisplay()));
                table.setTableTypeCode(trimToEmpty(rs.getString("TABLE_TYPE")));
                table.setTableComm(trimToEmpty(rs.getString("REMARKS")));
                tables.add(table);
            }
        }
        return tables;
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        Scope scope = resolveScope(conn, databaseName);
        NameParts parts = parseName(tableName);
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(
                firstNonBlank(scope.catalog(), parts.catalog()),
                firstNonBlank(parts.schema(), scope.schemaPattern()),
                parts.objectName(),
                new String[]{"TABLE"})) {
            if (rs.next()) {
                Table table = new Table(trimToEmpty(rs.getString("TABLE_NAME")));
                table.setTableCatalog(firstNonBlank(rs.getString("TABLE_CAT"), scope.catalog(), scope.schemaDisplay()));
                table.setTableOwner(firstNonBlank(rs.getString("TABLE_SCHEM"), scope.schemaDisplay()));
                table.setTableTypeCode(trimToEmpty(rs.getString("TABLE_TYPE")));
                table.setTableComm(trimToEmpty(rs.getString("REMARKS")));
                return table;
            }
        }
        return new Table(blankToFallback(parts.objectName(), blankToFallback(tableName, "")));
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        NameParts parts = parseName(tableName);
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(parts.catalog(), parts.schema(), parts.objectName(), null)) {
            if (rs.next()) {
                return trimToEmpty(rs.getString("REMARKS"));
            }
        }
        return "";
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        ArrayList<ColumnsInfo> columns = new ArrayList<>();
        if (conn == null || tableName == null || tableName.isBlank()) {
            return columns;
        }
        DatabaseMetaData metaData = conn.getMetaData();
        NameParts parts = parseName(tableName);
        Set<String> primaryKeys = loadPrimaryKeys(metaData, parts);
        int ordinal = 1;
        try (ResultSet rs = metaData.getColumns(parts.catalog(), parts.schema(), parts.objectName(), null)) {
            while (rs.next()) {
                ColumnsInfo column = new ColumnsInfo();
                String name = trimToEmpty(rs.getString("COLUMN_NAME"));
                column.setColNo(rs.getInt("ORDINAL_POSITION") > 0 ? rs.getInt("ORDINAL_POSITION") : ordinal++);
                column.setColName(name);
                String typeName = trimToEmpty(rs.getString("TYPE_NAME"));
                column.setColType(typeName);
                int columnSize = rs.getInt("COLUMN_SIZE");
                column.setColLength(columnSize);
                column.setTypeP(columnSize);
                column.setTypeS(rs.getInt("DECIMAL_DIGITS"));
                column.setIsNullable(DatabaseMetaData.columnNullable == rs.getInt("NULLABLE"));
                column.setIsPK(primaryKeys.contains(name.toLowerCase(Locale.ROOT)));
                column.setColDef(rs.getString("COLUMN_DEF"));
                column.setColComm(trimToEmpty(rs.getString("REMARKS")));
                String autoIncrement = trimToEmpty(safeGetString(rs, "IS_AUTOINCREMENT"));
                column.setIsAutoincrement("YES".equalsIgnoreCase(autoIncrement));
                columns.add(column);
            }
        }
        return columns;
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        ArrayList<Index> indexes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Scope scope = resolveScope(conn, databaseName);
        for (Table table : getUserTables(conn, databaseName)) {
            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            Map<String, Boolean> nonUniqueByIndex = new LinkedHashMap<>();
            try (ResultSet rs = metaData.getIndexInfo(scope.catalog(), scope.schemaPattern(), table.getName(), false, false)) {
                while (rs.next()) {
                    String indexName = trimToEmpty(rs.getString("INDEX_NAME"));
                    if (indexName.isEmpty()) {
                        continue;
                    }
                    String columnName = trimToEmpty(rs.getString("COLUMN_NAME"));
                    indexColumns.computeIfAbsent(indexName, key -> new ArrayList<>());
                    if (!columnName.isEmpty()) {
                        indexColumns.get(indexName).add(columnName);
                    }
                    nonUniqueByIndex.putIfAbsent(indexName, rs.getBoolean("NON_UNIQUE"));
                }
            }
            for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
                Index index = new Index(entry.getKey());
                index.setDatabase(blankToFallback(databaseName, firstNonBlank(scope.catalog(), scope.schemaDisplay(), "DEFAULT")));
                index.setTabname(table.getName());
                index.setCols(String.join(", ", entry.getValue()));
                index.setIdxtype(Boolean.TRUE.equals(nonUniqueByIndex.get(entry.getKey())) ? "NONUNIQUE" : "UNIQUE");
                indexes.add(index);
            }
        }
        return indexes;
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        return getIndexes(conn, currentScopeName(conn)).size();
    }

    @Override
    public String getIndexSize(Connection conn) {
        return "";
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        for (Index index : getIndexes(conn, databaseName)) {
            if (index.getName().equalsIgnoreCase(indexName)) {
                return index;
            }
        }
        return new Index(indexName);
    }

    @Override
    public int getSequenceCount(Connection conn) {
        return 0;
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public int getSynonymCount(Connection conn) {
        return 0;
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public int getTriggerCount(Connection conn) {
        return 0;
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) {
        return new Trigger(triggerName);
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        return countTables(conn, "VIEW");
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        ArrayList<View> views = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Scope scope = resolveScope(conn, databaseName);
        try (ResultSet rs = metaData.getTables(scope.catalog(), scope.schemaPattern(), "%", new String[]{"VIEW"})) {
            while (rs.next()) {
                View view = new View(trimToEmpty(rs.getString("TABLE_NAME")));
                view.setDbname(firstNonBlank(scope.catalog(), scope.schemaDisplay(), databaseName));
                view.setOwner(trimToEmpty(rs.getString("TABLE_SCHEM")));
                view.setCreateTime("");
                views.add(view);
            }
        }
        return views;
    }

    @Override
    public int getSystemDualTabId(Connection conn) {
        return 0;
    }

    @Override
    public boolean hasSysProcTypeColumn(Connection conn) {
        return false;
    }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        return getFunctions(conn, currentScopeName(conn), filterType).size();
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        ArrayList<Function> functions = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Scope scope = resolveScope(conn, databaseName);
        try (ResultSet rs = metaData.getFunctions(scope.catalog(), scope.schemaPattern(), "%")) {
            while (rs.next()) {
                Function function = new Function(trimToEmpty(rs.getString("FUNCTION_NAME")));
                function.setDatabase(firstNonBlank(scope.catalog(), scope.schemaDisplay(), databaseName));
                function.setOwner(trimToEmpty(rs.getString("FUNCTION_SCHEM")));
                functions.add(function);
            }
        } catch (SQLException ignored) {
            // some drivers do not support this metadata
        }
        return functions;
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        return getProcedures(conn, currentScopeName(conn), filterType).size();
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        ArrayList<Procedure> procedures = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Scope scope = resolveScope(conn, databaseName);
        try (ResultSet rs = metaData.getProcedures(scope.catalog(), scope.schemaPattern(), "%")) {
            while (rs.next()) {
                Procedure procedure = new Procedure(trimToEmpty(rs.getString("PROCEDURE_NAME")));
                procedure.setDatabase(firstNonBlank(scope.catalog(), scope.schemaDisplay(), databaseName));
                procedure.setOwner(trimToEmpty(rs.getString("PROCEDURE_SCHEM")));
                procedures.add(procedure);
            }
        } catch (SQLException ignored) {
            // some drivers do not support this metadata
        }
        return procedures;
    }

    @Override
    public int getPackageCount(Connection conn) {
        return 0;
    }

    @Override
    public List<com.dbboys.vo.DBPackage> getPackages(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public List<String> getStorageSpacesForCreateDatabase(Connection conn) {
        return List.of();
    }

    @Override
    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        setDatabase(conn, databaseName);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        try {
            conn.setCatalog(databaseName);
            return;
        } catch (SQLException ignored) {
            // fall through
        }
        try {
            conn.setSchema(databaseName);
        } catch (Throwable ignored) {
            // best effort only
        }
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        ArrayList<String> columns = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        NameParts parts = parseName(tableName);
        try (ResultSet rs = metaData.getIndexInfo(parts.catalog(), parts.schema(), parts.objectName(), false, false)) {
            while (rs.next()) {
                String columnName = trimToEmpty(rs.getString("COLUMN_NAME"));
                if (!columnName.isEmpty() && !columns.contains(columnName)) {
                    columns.add(columnName);
                }
            }
        }
        return columns;
    }

    private int countTables(Connection conn, String type) throws SQLException {
        if (conn == null) {
            return 0;
        }
        int count = 0;
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(conn.getCatalog(), null, "%", new String[]{type})) {
            while (rs.next()) {
                count++;
            }
        }
        return count;
    }

    private Set<String> loadPrimaryKeys(DatabaseMetaData metaData, NameParts parts) throws SQLException {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(parts.catalog(), parts.schema(), parts.objectName())) {
            while (rs.next()) {
                String columnName = trimToEmpty(rs.getString("COLUMN_NAME"));
                if (!columnName.isEmpty()) {
                    keys.add(columnName.toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    private Scope resolveScope(Connection conn, String databaseName) throws SQLException {
        String currentCatalog = trimToEmpty(conn == null ? null : conn.getCatalog());
        String currentSchema = "";
        if (conn != null) {
            try {
                currentSchema = trimToEmpty(conn.getSchema());
            } catch (Throwable ignored) {
                currentSchema = "";
            }
        }
        String database = trimToEmpty(databaseName);
        if (database.isEmpty()) {
            return new Scope(currentCatalog, currentSchema, currentSchema);
        }
        if (!currentCatalog.isEmpty() && database.equalsIgnoreCase(currentCatalog)) {
            return new Scope(currentCatalog, currentSchema, database);
        }
        if (!currentSchema.isEmpty() && database.equalsIgnoreCase(currentSchema)) {
            return new Scope(currentCatalog, currentSchema, database);
        }
        if (currentCatalog.isEmpty()) {
            return new Scope(null, database, database);
        }
        return new Scope(database, null, database);
    }

    private String currentScopeName(Connection conn) throws SQLException {
        String catalog = trimToEmpty(conn == null ? null : conn.getCatalog());
        if (!catalog.isEmpty()) {
            return catalog;
        }
        try {
            String schema = trimToEmpty(conn == null ? null : conn.getSchema());
            if (!schema.isEmpty()) {
                return schema;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "DEFAULT";
    }

    private Catalog createCatalog(String name) {
        Catalog catalog = new Catalog(name);
        catalog.setDbOwner("");
        catalog.setDbLog("");
        catalog.setDbSpace("");
        catalog.setDbSize("");
        catalog.setDbCreated("");
        catalog.setDbLocale("");
        catalog.setDbUseGLU("");
        return catalog;
    }

    private NameParts parseName(String rawName) {
        if (rawName == null) {
            return new NameParts(null, null, "");
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < rawName.length(); i++) {
            char ch = rawName.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            }
            if (ch == '.' && !inQuotes) {
                parts.add(unquote(current.toString()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(unquote(current.toString()));
        if (parts.size() >= 3) {
            return new NameParts(parts.get(parts.size() - 3), parts.get(parts.size() - 2), parts.get(parts.size() - 1));
        }
        if (parts.size() == 2) {
            return new NameParts(null, parts.get(0), parts.get(1));
        }
        return new NameParts(null, null, parts.get(0));
    }

    private static String unquote(String value) {
        String text = trimToEmpty(value);
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("`") && text.endsWith("`"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static String safeGetString(ResultSet rs, String columnLabel) {
        try {
            return rs.getString(columnLabel);
        } catch (SQLException e) {
            return "";
        }
    }

    private static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    private static String blankToFallback(String text, String fallback) {
        String trimmed = trimToEmpty(text);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record Scope(String catalog, String schemaPattern, String schemaDisplay) {
    }

    private record NameParts(String catalog, String schema, String objectName) {
    }
}
