package com.dbboys.dialect.db2;

import com.dbboys.core.MetadataRepository;
import com.dbboys.infra.db.SqlRunner;
import com.dbboys.model.*;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Db2 metadata repository backed by the SYSCAT catalog views.
 * Tree level 1 lists schemas (SCHEMA catalog model, like Oracle).
 */
public final class Db2MetadataRepository implements MetadataRepository {

    /** SQLState thrown to request a reconnect-based database switch. */
    public static final String RECONNECT_REQUIRED_STATE = "25P01";

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final String SYSTEM_SCHEMA_FILTER =
            "SCHEMANAME NOT LIKE 'SYS%' AND SCHEMANAME NOT IN ('NULLID', 'SQLJ', 'SYSTOOLS', 'SYSIBMADM', 'SYSPUBLIC')";

    private static final String SQL_SCHEMAS = """
            SELECT SCHEMANAME, OWNER, CREATE_TIME
            FROM SYSCAT.SCHEMATA
            WHERE """ + SYSTEM_SCHEMA_FILTER + """
            ORDER BY SCHEMANAME
            """;

    private static final String SQL_SCHEMA_INFO = """
            SELECT SCHEMANAME, OWNER, CREATE_TIME
            FROM SYSCAT.SCHEMATA
            WHERE SCHEMANAME = ?
            """;

    private static final String SQL_TABLES = """
            SELECT TABNAME, OWNER, CREATE_TIME,
                   COALESCE(REMARKS, '') AS TAB_COMMENT,
                   COALESCE(CARD, 0) AS NUM_ROWS
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TYPE IN ('T', 'S', 'N', 'H', 'U')
            ORDER BY TABNAME
            """;

    private static final String SQL_TABLE_DETAIL = """
            SELECT TABNAME, OWNER, CREATE_TIME,
                   COALESCE(REMARKS, '') AS TAB_COMMENT,
                   COALESCE(CARD, 0) AS NUM_ROWS
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TABNAME = ?
            """;

    private static final String SQL_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TYPE IN ('T', 'S', 'N', 'H', 'U')
            """;

    private static final String SQL_TABLE_COMMENT = """
            SELECT COALESCE(REMARKS, '')
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TABNAME = ?
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLNAME, TYPENAME, LENGTH, SCALE, NULLS, DEFAULT, REMARKS,
                   IDENTITY, COLNO
            FROM SYSCAT.COLUMNS
            WHERE TABSCHEMA = ?
              AND TABNAME = ?
              AND HIDDEN = ''
            ORDER BY COLNO
            """;

    private static final String SQL_PK_COLUMNS = """
            SELECT k.COLNAME
            FROM SYSCAT.TABCONST c
            JOIN SYSCAT.KEYCOLUSE k
              ON k.CONSTNAME = c.CONSTNAME
             AND k.TABSCHEMA = c.TABSCHEMA
             AND k.TABNAME = c.TABNAME
            WHERE c.TABSCHEMA = ?
              AND c.TABNAME = ?
              AND c.TYPE = 'P'
            ORDER BY k.COLSEQ
            """;

    private static final String SQL_INDEXES = """
            SELECT i.INDNAME, i.TABNAME, i.INDEXTYPE, i.UNIQUERULE,
                   COALESCE(i.REMARKS, '') AS IDX_COMMENT
            FROM SYSCAT.INDEXES i
            WHERE i.TABSCHEMA = ?
            ORDER BY i.INDNAME
            """;

    private static final String SQL_INDEX_DETAIL = """
            SELECT i.INDNAME, i.TABNAME, i.INDEXTYPE, i.UNIQUERULE
            FROM SYSCAT.INDEXES i
            WHERE i.INDSCHEMA = ?
              AND i.INDNAME = ?
            """;

    private static final String SQL_INDEX_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.INDEXES
            WHERE TABSCHEMA = ?
            """;

    private static final String SQL_INDEX_COLUMNS = """
            SELECT c.COLNAME, c.COLORDER
            FROM SYSCAT.INDEXCOLUSE c
            JOIN SYSCAT.INDEXES i
              ON i.INDSCHEMA = c.INDSCHEMA
             AND i.INDNAME = c.INDNAME
            WHERE i.TABSCHEMA = ?
              AND i.TABNAME = ?
            ORDER BY c.INDNAME, c.COLSEQ
            """;

    private static final String SQL_INDEX_COLUMNS_OF = """
            SELECT c.COLNAME, c.COLORDER
            FROM SYSCAT.INDEXCOLUSE c
            WHERE c.INDSCHEMA = ?
              AND c.INDNAME = ?
            ORDER BY c.COLSEQ
            """;

    private static final String SQL_VIEWS = """
            SELECT VIEWNAME, OWNER, CREATE_TIME
            FROM SYSCAT.VIEWS
            WHERE VIEWSCHEMA = ?
            ORDER BY VIEWNAME
            """;

    private static final String SQL_VIEW_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.VIEWS
            WHERE VIEWSCHEMA = ?
            """;

    private static final String SQL_SEQUENCES = """
            SELECT SEQNAME, MINVALUE, MAXVALUE, INCREMENT, CACHE, NEXTCACHEFIRSTVALUE
            FROM SYSCAT.SEQUENCES
            WHERE SEQSCHEMA = ?
              AND SEQTYPE = 'S'
            ORDER BY SEQNAME
            """;

    private static final String SQL_SEQUENCE_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.SEQUENCES
            WHERE SEQSCHEMA = ?
              AND SEQTYPE = 'S'
            """;

    private static final String SQL_SYNONYMS = """
            SELECT SYNNAME, BASETABSCHEMA, BASETABNAME, CREATE_TIME
            FROM SYSCAT.SYNONYMS
            WHERE TABSCHEMA = ?
            ORDER BY SYNNAME
            """;

    private static final String SQL_SYNONYM_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.SYNONYMS
            WHERE TABSCHEMA = ?
            """;

    private static final String SQL_TRIGGERS = """
            SELECT TRIGNAME, TABNAME, TRIGEVENT, TRIGTIME, CREATE_TIME
            FROM SYSCAT.TRIGGERS
            WHERE TRIGSCHEMA = ?
            ORDER BY TRIGNAME
            """;

    private static final String SQL_TRIGGER_DETAIL = """
            SELECT TRIGNAME, TABNAME, TRIGEVENT, TRIGTIME
            FROM SYSCAT.TRIGGERS
            WHERE TRIGSCHEMA = ?
              AND TRIGNAME = ?
            """;

    private static final String SQL_TRIGGER_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.TRIGGERS
            WHERE TRIGSCHEMA = ?
            """;

    private static final String SQL_ROUTINES = """
            SELECT ROUTINENAME, OWNER, CREATE_TIME
            FROM SYSCAT.ROUTINES
            WHERE ROUTINESCHEMA = ?
              AND ROUTINETYPE = ?
            ORDER BY ROUTINENAME, SPECIFICNAME
            """;

    private static final String SQL_ROUTINE_COUNT = """
            SELECT COUNT(DISTINCT ROUTINENAME)
            FROM SYSCAT.ROUTINES
            WHERE ROUTINESCHEMA = ?
              AND ROUTINETYPE = ?
            """;

    private static final String SQL_SYSTEM_TABLES = """
            SELECT TABNAME, COALESCE(REMARKS, '') AS TAB_COMMENT
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = 'SYSCAT'
            ORDER BY TABNAME
            """;

    private static final String SQL_SYSTEM_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = 'SYSCAT'
            """;

    // ==================================================================

    @Override
    public List<User> getUsers(Connection conn) {
        return List.of();
    }

    @Override
    public List<Database> getDatabases(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SCHEMAS, null, rs -> {
            Database schema = new Database(rs.getString("SCHEMANAME").trim());
            schema.setDbOwner(blank(rs.getString("OWNER")));
            schema.setDbCreated(formatTimestamp(rs.getString("CREATE_TIME")));
            schema.setDbLog("");
            schema.setDbSpace("");
            schema.setDbSize("");
            schema.setDbLocale("");
            schema.setDbUseGLU("");
            return schema;
        });
    }

    @Override
    public CatalogNode getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Database info = runner.queryOne(SQL_SCHEMA_INFO, List.of(schemaName(databaseName)), rs -> {
            Database schema = new Database(rs.getString("SCHEMANAME").trim());
            schema.setDbOwner(blank(rs.getString("OWNER")));
            schema.setDbCreated(formatTimestamp(rs.getString("CREATE_TIME")));
            schema.setDbLog("");
            schema.setDbSpace("");
            schema.setDbSize("");
            schema.setDbLocale("");
            schema.setDbUseGLU("");
            return schema;
        });
        if (info != null) {
            return info;
        }
        Database fallback = new Database(databaseName == null ? "" : databaseName.trim());
        fallback.setDbSize("");
        return fallback;
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_TABLES_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) {
        return null;
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SYSTEM_TABLES_COUNT, null, rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) {
        return null;
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SYSTEM_TABLES, null, rs -> {
            SysTable table = new SysTable(rs.getString("TABNAME").trim());
            table.setTableCatalog("SYSCAT");
            table.setTableComm(blank(rs.getString("TAB_COMMENT")));
            return table;
        });
    }

    @Override
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_TABLES, List.of(schemaName(databaseName)), rs -> {
            Table table = mapTable(rs);
            return table;
        });
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        Table table = runner.queryOne(SQL_TABLE_DETAIL,
                List.of(schemaName(databaseName), objectName(tableName)), rs -> mapTable(rs));
        return table != null ? table : new Table(objectName(tableName));
    }

    private static Table mapTable(java.sql.ResultSet rs) throws SQLException {
        Table table = new Table(rs.getString("TABNAME").trim());
        table.setTableCatalog(rs.getString("OWNER") == null ? "" : rs.getString("OWNER").trim());
        table.setTableOwner(table.getTableCatalog());
        table.setTableComm(blank(rs.getString("TAB_COMMENT")));
        table.setNrows(rs.getInt("NUM_ROWS"));
        table.setCreateTime(formatTimestamp(rs.getString("CREATE_TIME")));
        return table;
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        String comment = runner.queryOne(SQL_TABLE_COMMENT, List.of(parts[0], parts[1]), rs -> rs.getString(1));
        return comment == null ? "" : comment;
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        List<String> pkColumns = runner.query(SQL_PK_COLUMNS, List.of(parts[0], parts[1]),
                rs -> rs.getString("COLNAME").trim());
        List<ColumnsInfo> columns = runner.query(SQL_COLUMNS, List.of(parts[0], parts[1]), rs -> {
            ColumnsInfo column = new ColumnsInfo();
            String name = rs.getString("COLNAME").trim();
            column.setColName(name);
            column.setColNo(rs.getInt("COLNO") + 1);
            String typeName = rs.getString("TYPENAME").trim();
            column.setColType(typeName);
            int length = rs.getInt("LENGTH");
            int scale = rs.getInt("SCALE");
            column.setTypeP(isTextualType(typeName) || "DECIMAL".equals(typeName) || "NUMERIC".equals(typeName)
                    ? length : 0);
            column.setTypeS(scale);
            column.setColLength(length);
            column.setIsNullable("Y".equalsIgnoreCase(rs.getString("NULLS")));
            column.setColDef(rs.getString("DEFAULT"));
            column.setColComm(blank(rs.getString("REMARKS")));
            column.setIsAutoincrement("Y".equalsIgnoreCase(rs.getString("IDENTITY")));
            column.setIsPK(pkColumns.contains(name));
            return column;
        });
        return new ArrayList<>(columns);
    }

    @Override
    public List<String> getPrimaryKeyColumns(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        return runner.query(SQL_PK_COLUMNS, List.of(parts[0], parts[1]), rs -> rs.getString("COLNAME").trim());
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_INDEXES, List.of(schemaName(databaseName)), rs -> {
            Index index = new Index(rs.getString("INDNAME").trim());
            index.setDatabase(schemaName(databaseName));
            index.setTabname(rs.getString("TABNAME").trim());
            index.setIdxtype("P".equals(rs.getString("UNIQUERULE")) ? "PRIMARY"
                    : "U".equals(rs.getString("UNIQUERULE")) ? "UNIQUE" : "NONUNIQUE");
            index.setCols(loadIndexColumns(conn, schemaName(databaseName), rs.getString("INDNAME").trim()));
            return index;
        });
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_INDEX_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getIndexSize(Connection conn) {
        return null;
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        SqlRunner runner = runner(conn);
        Index index = runner.queryOne(SQL_INDEX_DETAIL,
                List.of(schemaName(databaseName), objectName(indexName)), rs -> {
                    Index idx = new Index(rs.getString("INDNAME").trim());
                    idx.setDatabase(schemaName(databaseName));
                    idx.setTabname(rs.getString("TABNAME").trim());
                    idx.setIdxtype("P".equals(rs.getString("UNIQUERULE")) ? "PRIMARY"
                            : "U".equals(rs.getString("UNIQUERULE")) ? "UNIQUE" : "NONUNIQUE");
                    return idx;
                });
        return index != null ? index : new Index(objectName(indexName));
    }

    private String loadIndexColumns(Connection conn, String indSchema, String indName) {
        try {
            SqlRunner runner = runner(conn);
            List<String> cols = runner.query(SQL_INDEX_COLUMNS_OF, List.of(indSchema, indName), rs -> {
                String name = rs.getString("COLNAME").trim();
                return "D".equals(rs.getString("COLORDER")) ? name + " DESC" : name;
            });
            return String.join(", ", cols);
        } catch (SQLException e) {
            return "";
        }
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        return runner.query(SQL_INDEX_COLUMNS, List.of(parts[0], parts[1]), rs -> rs.getString("COLNAME").trim());
    }

    @Override
    public int getSequenceCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SEQUENCE_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SEQUENCES, List.of(schemaName(databaseName)), rs -> {
            Sequence sequence = new Sequence(rs.getString("SEQNAME").trim());
            sequence.setDatabase(schemaName(databaseName));
            sequence.setMinValue(toBigInteger(rs.getString("MINVALUE"), BigInteger.ONE));
            sequence.setMaxValue(toBigInteger(rs.getString("MAXVALUE"), new BigInteger("9223372036854775807")));
            sequence.setIncValue(toBigInteger(rs.getString("INCREMENT"), BigInteger.ONE));
            sequence.setCache(rs.getLong("CACHE"));
            sequence.setNextVal(toBigInteger(rs.getString("NEXTCACHEFIRSTVALUE"), BigInteger.ONE));
            return sequence;
        });
    }

    @Override
    public int getSynonymCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SYNONYM_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SYNONYMS, List.of(schemaName(databaseName)), rs -> {
            Synonym synonym = new Synonym(rs.getString("SYNNAME").trim());
            synonym.setDatabase(schemaName(databaseName));
            String baseSchema = blank(rs.getString("BASETABSCHEMA"));
            String baseName = blank(rs.getString("BASETABNAME"));
            synonym.setSynonymType(baseSchema.isEmpty() ? baseName : baseSchema + "." + baseName);
            synonym.setCreated(formatTimestamp(rs.getString("CREATE_TIME")));
            return synonym;
        });
    }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_TRIGGER_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_TRIGGERS, List.of(schemaName(databaseName)), rs -> mapTrigger(rs, databaseName));
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        SqlRunner runner = runner(conn);
        Trigger trigger = runner.queryOne(SQL_TRIGGER_DETAIL,
                List.of(schemaName(databaseName), objectName(triggerName)), rs -> mapTrigger(rs, databaseName));
        return trigger != null ? trigger : new Trigger(objectName(triggerName));
    }

    private static Trigger mapTrigger(java.sql.ResultSet rs, String databaseName) throws SQLException {
        Trigger trigger = new Trigger(rs.getString("TRIGNAME").trim());
        trigger.setDatabase(schemaName(databaseName));
        trigger.setTableName(rs.getString("TABNAME") == null ? "" : rs.getString("TABNAME").trim());
        trigger.setTriggerType(triggerTypeText(rs.getString("TRIGTIME"), rs.getString("TRIGEVENT")));
        return trigger;
    }

    private static String triggerTypeText(String trigTime, String trigEvent) {
        String time = switch (trigTime == null ? "" : trigTime.trim()) {
            case "B" -> "BEFORE";
            case "A" -> "AFTER";
            case "I" -> "INSTEAD OF";
            default -> "";
        };
        String event = switch (trigEvent == null ? "" : trigEvent.trim()) {
            case "I" -> "INSERT";
            case "U" -> "UPDATE";
            case "D" -> "DELETE";
            default -> "";
        };
        return (time + " " + event).trim();
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_VIEW_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_VIEWS, List.of(schemaName(databaseName)), rs -> {
            View view = new View(rs.getString("VIEWNAME").trim());
            view.setDbname(schemaName(databaseName));
            view.setOwner(blank(rs.getString("OWNER")));
            view.setCreateTime(formatTimestamp(rs.getString("CREATE_TIME")));
            return view;
        });
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
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_ROUTINE_COUNT, List.of(currentSchema(conn), "F"), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_ROUTINES, List.of(schemaName(databaseName), "F"), rs -> {
            Function function = new Function(rs.getString("ROUTINENAME").trim());
            function.setDatabase(schemaName(databaseName));
            function.setOwner(blank(rs.getString("OWNER")));
            return function;
        });
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_ROUTINE_COUNT, List.of(currentSchema(conn), "P"), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_ROUTINES, List.of(schemaName(databaseName), "P"), rs -> {
            Procedure procedure = new Procedure(rs.getString("ROUTINENAME").trim());
            procedure.setDatabase(schemaName(databaseName));
            procedure.setOwner(blank(rs.getString("OWNER")));
            return procedure;
        });
    }

    @Override
    public int getPackageCount(Connection conn) {
        return 0;
    }

    @Override
    public List<DBPackage> getPackages(Connection conn, String databaseName) {
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
        String schema = databaseName.trim().replace("\"", "\"\"");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET CURRENT SCHEMA \"" + schema + "\"");
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        SqlRunner runner = runner(conn);
        String schema = runner.queryOne("SELECT CURRENT SCHEMA FROM SYSIBM.SYSDUMMY1", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema.trim() : "";
    }

    private static String schemaName(String databaseName) {
        return databaseName == null ? "" : databaseName.trim();
    }

    private static String objectName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1).replace("\"", "") : trimmed.replace("\"", "");
    }

    /** Splits "schema.table" into [schema, table]; unqualified names use the given default schema. */
    private static String[] splitQualified(String name, String defaultSchema) {
        String trimmed = name == null ? "" : name.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot > 0) {
            return new String[]{trimmed.substring(0, dot).replace("\"", ""), trimmed.substring(dot + 1).replace("\"", "")};
        }
        return new String[]{defaultSchema == null ? "" : defaultSchema.trim(), trimmed.replace("\"", "")};
    }

    private static boolean isTextualType(String typeName) {
        return typeName != null && (typeName.contains("CHAR") || typeName.contains("CLOB")
                || typeName.contains("BINARY") || typeName.contains("GRAPHIC"));
    }

    private static BigInteger toBigInteger(String value, BigInteger fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigInteger(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatTimestamp(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private static SqlRunner runner(Connection conn) {
        return new SqlRunner(conn, QUERY_TIMEOUT_SECONDS);
    }
}
