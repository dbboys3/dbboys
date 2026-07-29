package com.dbboys.dialect.mysql;

import com.dbboys.core.MetadataRepository;
import com.dbboys.model.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MysqlMetadataRepository implements MetadataRepository {

    private static final Set<String> SYSTEM_DATABASES =
            Set.of("information_schema", "mysql", "performance_schema", "sys");

    @Override
    public List<User> getUsers(Connection conn) throws SQLException {
        String sql = """
                select user, host
                from mysql.user
                order by case when user in ('root', 'mysql.sys', 'mysql.session', 'mysql.infoschema') then 0 else 1 end,
                         user,
                         host
                """;
        List<User> users = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(new User(toAccountName(rs.getString("user"), rs.getString("host"))));
            }
        } catch (SQLException e) {
            users.addAll(getUsersFromPrivileges(conn));
        }
        return users;
    }

    @Override
    public boolean supportsUsers(Connect connect) {
        return true;
    }

    @Override
    public List<Database> getDatabases(Connection conn) throws SQLException {
        List<Database> databases = new ArrayList<>();
        String sql = """
                select s.schema_name,
                       s.default_character_set_name,
                       s.default_collation_name,
                       coalesce(sum(coalesce(t.data_length, 0) + coalesce(t.index_length, 0)), 0) as bytes
                from information_schema.schemata s
                left join information_schema.tables t on t.table_schema = s.schema_name
                group by s.schema_name, s.default_character_set_name, s.default_collation_name
                order by case when lower(s.schema_name) in ('information_schema', 'mysql', 'performance_schema', 'sys') then 0 else 1 end,
                         s.schema_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Database catalog = new Database(rs.getString("schema_name"));
                catalog.setDbOwner("");
                catalog.setDbLog("");
                catalog.setDbUseGLU("");
                catalog.setDbLocale(blankToEmpty(rs.getString("default_character_set_name"))
                        + "/" + blankToEmpty(rs.getString("default_collation_name")));
                catalog.setDbSpace("");
                catalog.setDbSize(formatBytes(rs.getLong("bytes")));
                catalog.setDbCreated("");
                databases.add(catalog);
            }
        }
        return databases;
    }

    @Override
    public Database getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        Database catalog = new Database(db);
        String sql = """
                select s.schema_name,
                       s.default_character_set_name,
                       s.default_collation_name,
                       coalesce(sum(coalesce(t.data_length, 0) + coalesce(t.index_length, 0)), 0) as bytes
                from information_schema.schemata s
                left join information_schema.tables t on t.table_schema = s.schema_name
                where s.schema_name = ?
                group by s.schema_name, s.default_character_set_name, s.default_collation_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    catalog.setDbLocale(blankToEmpty(rs.getString("default_character_set_name"))
                            + "/" + blankToEmpty(rs.getString("default_collation_name")));
                    catalog.setDbSize(formatBytes(rs.getLong("bytes")));
                }
            }
        }
        catalog.setDbOwner("");
        catalog.setDbLog("");
        catalog.setDbSpace("");
        catalog.setDbCreated("");
        catalog.setDbUseGLU("");
        return catalog;
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        return countObjects(conn, currentDatabase(conn, null), "BASE TABLE");
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        return tableSize(conn, currentDatabase(conn, databaseName), "BASE TABLE");
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
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
        List<Table> tables = new ArrayList<>();
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select table_name, table_type, engine, table_rows, create_time, table_comment,
                       data_length, index_length
                from information_schema.tables
                where table_schema = ? and table_type = 'BASE TABLE'
                order by table_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(readTable(db, rs));
                }
            }
        }
        return tables;
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select table_name, table_type, engine, table_rows, create_time, table_comment,
                       data_length, index_length
                from information_schema.tables
                where table_schema = ? and table_name = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, objectName(tableName));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readTable(db, rs);
                }
            }
        }
        return new Table(objectName(tableName));
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        QualifiedName qn = qualify(conn, tableName);
        String sql = "select table_comment from information_schema.tables where table_schema = ? and table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qn.database());
            ps.setString(2, qn.object());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? blankToEmpty(rs.getString(1)) : "";
            }
        }
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        QualifiedName qn = qualify(conn, tableName);
        Set<String> pks = new LinkedHashSet<>(getPrimaryKeyColumns(conn, tableName));
        ArrayList<ColumnsInfo> columns = new ArrayList<>();
        String sql = """
                select ordinal_position, column_name, data_type, column_type, character_maximum_length,
                       numeric_precision, numeric_scale, is_nullable, column_default, column_comment, extra
                from information_schema.columns
                where table_schema = ? and table_name = ?
                order by ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qn.database());
            ps.setString(2, qn.object());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnsInfo column = new ColumnsInfo();
                    String name = rs.getString("column_name");
                    column.setColNo(rs.getInt("ordinal_position"));
                    column.setColName(name);
                    String columnType = blankToEmpty(rs.getString("column_type"));
                    if (!columnType.isEmpty()) {
                        applyMysqlColumnTypeFromDefinition(column, columnType);
                    } else {
                        column.setColType(blankToEmpty(rs.getString("data_type")).toUpperCase(Locale.ROOT));
                        int length = firstPositive(rs.getLong("character_maximum_length"), rs.getLong("numeric_precision"));
                        column.setColLength(length);
                        column.setTypeP(length);
                        column.setTypeS(Math.max(rs.getInt("numeric_scale"), 0));
                    }
                    column.setIsNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    column.setIsPK(pks.contains(name.toLowerCase(Locale.ROOT)));
                    column.setColDef(rs.getString("column_default"));
                    column.setColComm(blankToEmpty(rs.getString("column_comment")));
                    boolean autoIncrement = blankToEmpty(rs.getString("extra")).toLowerCase(Locale.ROOT).contains("auto_increment")
                            || blankToEmpty(rs.getString("column_type")).toLowerCase(Locale.ROOT).contains("auto_increment")
                            || isAutoIncrementColumn(conn, qn.database(), qn.object(), name);
                    column.setIsAutoincrement(autoIncrement);
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    private static boolean isAutoIncrementColumn(Connection conn, String database, String table, String column) {
        if (conn == null || table == null || column == null) {
            return false;
        }
        try (ResultSet rs = conn.getMetaData().getColumns(database, null, table, column)) {
            while (rs.next()) {
                String value = rs.getString("IS_AUTOINCREMENT");
                if ("YES".equalsIgnoreCase(value)) {
                    return true;
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select table_schema, table_name, index_name, non_unique,
                       group_concat(column_name order by seq_in_index separator ', ') as columns
                from information_schema.statistics
                where table_schema = ?
                group by table_schema, table_name, index_name, non_unique
                order by table_name, index_name
                """;
        List<Index> indexes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Index index = new Index(rs.getString("index_name"));
                    index.setDatabase(rs.getString("table_schema"));
                    index.setTabname(rs.getString("table_name"));
                    index.setTableName(rs.getString("table_name"));
                    index.setIndexOwner(rs.getString("table_schema"));
                    index.setCols(blankToEmpty(rs.getString("columns")));
                    index.setIdxtype(rs.getInt("non_unique") == 0 ? "UNIQUE" : "NONUNIQUE");
                    index.setIsdisabled(false);
                    indexes.add(index);
                }
            }
        }
        return indexes;
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        String sql = "select count(distinct table_schema, table_name, index_name) from information_schema.statistics where table_schema = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentDatabase(conn, null));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public String getIndexSize(Connection conn) throws SQLException {
        String sql = "select coalesce(sum(index_length), 0) from information_schema.tables where table_schema = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentDatabase(conn, null));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? formatBytes(rs.getLong(1)) : "";
            }
        }
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        for (Index index : getIndexes(conn, databaseName)) {
            if (index.getName().equalsIgnoreCase(objectName(indexName))) {
                return index;
            }
        }
        return new Index(objectName(indexName));
    }

    @Override public int getSequenceCount(Connection conn) { return 0; }
    @Override public List<Sequence> getSequences(Connection conn, String databaseName) { return List.of(); }
    @Override public int getSynonymCount(Connection conn) { return 0; }
    @Override public List<Synonym> getSynonyms(Connection conn, String databaseName) { return List.of(); }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        return countBySchema(conn, "information_schema.triggers", "trigger_schema");
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select trigger_name, event_object_table, action_timing, event_manipulation
                from information_schema.triggers
                where trigger_schema = ?
                order by trigger_name
                """;
        List<Trigger> triggers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Trigger trigger = new Trigger(rs.getString("trigger_name"));
                    trigger.setDatabase(db);
                    trigger.setTableName(rs.getString("event_object_table"));
                    trigger.setTriggerType(rs.getString("action_timing") + " " + rs.getString("event_manipulation"));
                    trigger.setIsdisabled(false);
                    triggers.add(trigger);
                }
            }
        }
        return triggers;
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        for (Trigger trigger : getTriggers(conn, databaseName)) {
            if (trigger.getName().equalsIgnoreCase(objectName(triggerName))) {
                return trigger;
            }
        }
        return new Trigger(objectName(triggerName));
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        return countObjects(conn, currentDatabase(conn, null), "VIEW");
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select table_name, create_time
                from information_schema.tables
                where table_schema = ? and table_type = 'VIEW'
                order by table_name
                """;
        List<View> views = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    View view = new View(rs.getString("table_name"));
                    view.setDbname(db);
                    view.setOwner(db);
                    view.setCreateTime(blankToEmpty(rs.getString("create_time")));
                    views.add(view);
                }
            }
        }
        return views;
    }

    @Override public int getSystemDualTabId(Connection conn) { return 0; }
    @Override public boolean hasSysProcTypeColumn(Connection conn) { return false; }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        return countRoutine(conn, "FUNCTION");
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select routine_name
                from information_schema.routines
                where routine_schema = ? and routine_type = 'FUNCTION'
                order by routine_name
                """;
        List<Function> functions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Function function = new Function(rs.getString("routine_name"));
                    function.setDatabase(db);
                    function.setOwner(db);
                    functions.add(function);
                }
            }
        }
        return functions;
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        return countRoutine(conn, "PROCEDURE");
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        String sql = """
                select routine_name
                from information_schema.routines
                where routine_schema = ? and routine_type = 'PROCEDURE'
                order by routine_name
                """;
        List<Procedure> procedures = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Procedure procedure = new Procedure(rs.getString("routine_name"));
                    procedure.setDatabase(db);
                    procedure.setOwner(db);
                    procedures.add(procedure);
                }
            }
        }
        return procedures;
    }

    @Override public int getPackageCount(Connection conn) { return 0; }
    @Override public List<DBPackage> getPackages(Connection conn, String databaseName) { return List.of(); }
    @Override public List<String> getStorageSpacesForCreateDatabase(Connection conn) { return List.of(); }

    @Override
    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        setDatabase(conn, databaseName);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn != null && databaseName != null && !databaseName.isBlank()) {
            conn.setCatalog(databaseName.trim());
        }
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        QualifiedName qn = qualify(conn, tableName);
        String sql = """
                select distinct column_name
                from information_schema.statistics
                where table_schema = ? and table_name = ?
                order by column_name
                """;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qn.database());
            ps.setString(2, qn.object());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    @Override
    public List<String> getPrimaryKeyColumns(Connection conn, String tableName) throws SQLException {
        QualifiedName qn = qualify(conn, tableName);
        TreeMap<Integer, String> columns = new TreeMap<>();
        String sql = """
                select column_name, seq_in_index
                from information_schema.statistics
                where table_schema = ? and table_name = ? and index_name = 'PRIMARY'
                order by seq_in_index
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qn.database());
            ps.setString(2, qn.object());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getInt("seq_in_index"), rs.getString("column_name").toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(columns.values());
    }

    private Table readTable(String db, ResultSet rs) throws SQLException {
        Table table = new Table(rs.getString("table_name"));
        table.setTableCatalog(db);
        table.setTableOwner(db);
        table.setTableTypeCode("BASE TABLE");
        table.setLockType(blankToEmpty(rs.getString("engine")));
        table.setCreateTime(blankToEmpty(rs.getString("create_time")));
        table.setTableComm(blankToEmpty(rs.getString("table_comment")));
        table.setNrows(Math.max(rs.getInt("table_rows"), 0));
        table.setTotalsize(formatBytes(rs.getLong("data_length") + rs.getLong("index_length")));
        table.setUsedsize(formatBytes(rs.getLong("data_length")));
        return table;
    }

    private int countObjects(Connection conn, String db, String tableType) throws SQLException {
        String sql = "select count(*) from information_schema.tables where table_schema = ? and table_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, tableType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String tableSize(Connection conn, String db, String tableType) throws SQLException {
        String sql = "select coalesce(sum(data_length + index_length), 0) from information_schema.tables where table_schema = ? and table_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, tableType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? formatBytes(rs.getLong(1)) : "";
            }
        }
    }

    private int countRoutine(Connection conn, String type) throws SQLException {
        String sql = "select count(*) from information_schema.routines where routine_schema = ? and routine_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentDatabase(conn, null));
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int countBySchema(Connection conn, String table, String schemaColumn) throws SQLException {
        String sql = "select count(*) from " + table + " where " + schemaColumn + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentDatabase(conn, null));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private QualifiedName qualify(Connection conn, String rawName) throws SQLException {
        String db = currentDatabase(conn, null);
        String name = objectName(rawName);
        if (rawName != null) {
            List<String> parts = splitName(rawName);
            if (parts.size() >= 2) {
                db = normalizeIdentifier(parts.get(parts.size() - 2));
                name = normalizeIdentifier(parts.get(parts.size() - 1));
            }
        }
        return new QualifiedName(db, name);
    }

    private String currentDatabase(Connection conn, String fallback) throws SQLException {
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        String catalog = conn == null ? "" : conn.getCatalog();
        return catalog == null || catalog.isBlank() ? "" : catalog.trim();
    }

    private static String objectName(String rawName) {
        if (rawName == null) {
            return "";
        }
        List<String> parts = splitName(rawName);
        return parts.isEmpty() ? "" : normalizeIdentifier(parts.get(parts.size() - 1));
    }

    private static List<String> splitName(String rawName) {
        List<String> parts = new ArrayList<>();
        if (rawName == null) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean inBacktick = false;
        for (int i = 0; i < rawName.length(); i++) {
            char ch = rawName.charAt(i);
            if (ch == '`') {
                inBacktick = !inBacktick;
                current.append(ch);
            } else if (ch == '.' && !inBacktick) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String normalizeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<User> getUsersFromPrivileges(Connection conn) throws SQLException {
        String sql = """
                select distinct grantee
                from information_schema.user_privileges
                order by grantee
                """;
        List<User> users = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(new User(normalizeAccountName(rs.getString("grantee"))));
            }
        }
        return users;
    }

    private static String toAccountName(String user, String host) {
        String userPart = user == null ? "" : user.trim();
        String hostPart = host == null || host.isBlank() ? "%" : host.trim();
        return userPart + "@" + hostPart;
    }

    private static String normalizeAccountName(String account) {
        String value = account == null ? "" : account.trim();
        if (value.isEmpty()) {
            return value;
        }
        int at = findAccountSeparator(value);
        if (at < 0) {
            return unquoteAccountPart(value) + "@%";
        }
        return unquoteAccountPart(value.substring(0, at)) + "@" + unquoteAccountPart(value.substring(at + 1));
    }

    private static int findAccountSeparator(String value) {
        boolean inQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                boolean escaped = i + 1 < value.length() && value.charAt(i + 1) == '\'';
                if (escaped) {
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (ch == '@' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static String unquoteAccountPart(String part) {
        String value = part == null ? "" : part.trim();
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static void applyMysqlColumnTypeFromDefinition(ColumnsInfo column, String columnType) {
        String normalized = columnType.trim();
        int open = normalized.indexOf('(');
        String basePart = open >= 0 ? normalized.substring(0, open).trim() : normalized;
        String baseWord = basePart.isEmpty() ? "" : basePart.split("\\s+")[0];
        column.setColType(baseWord.toUpperCase(Locale.ROOT));
        column.setColLength(0);
        column.setTypeP(0);
        column.setTypeS(0);
        if (open < 0) {
            return;
        }
        int close = normalized.indexOf(')', open);
        if (close <= open) {
            return;
        }
        String inside = normalized.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return;
        }
        String[] parts = inside.split(",");
        int precision = parseUnsignedInt(parts[0].trim());
        if (precision > 0) {
            column.setTypeP(precision);
            column.setColLength(precision);
        }
        if (parts.length > 1) {
            column.setTypeS(Math.max(parseUnsignedInt(parts[1].trim()), 0));
        }
    }

    private static int parseUnsignedInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int firstPositive(long left, long right) {
        long result = left > 0 ? left : right;
        if (result <= 0) {
            return 0;
        }
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 MB";
        }
        double mb = bytes / 1024d / 1024d;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.2f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }

    private record QualifiedName(String database, String object) {
    }
}
