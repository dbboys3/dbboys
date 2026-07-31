package com.dbboys.dialect.common;

import com.dbboys.core.DdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Shared DDL export implementation for the PostgreSQL family of dialects.
 * Uses {@code pg_get_*} functions and {@code information_schema} reconstruction for DDL generation.
 * Dialect differences are isolated behind the hook methods below.
 */
public abstract class PostgreSqlFamilyDdlRepository implements DdlRepository {

    protected static final int QUERY_TIMEOUT = 60;

    // ------------------------------------------------------------------
    // Dialect hooks
    // ------------------------------------------------------------------

    /** Default schema name when the connection does not report one. */
    protected abstract String defaultSchemaName();

    // ------------------------------------------------------------------
    // Export helpers
    // ------------------------------------------------------------------

    @Override
    public long countDatabaseExportItems(Connection conn, String databaseName) throws Exception {
        String schema = databaseName != null && !databaseName.isBlank() ? databaseName : currentSchema(conn);
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE')
                  + (SELECT COUNT(*) FROM information_schema.views WHERE table_schema = ?)
                  + (SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_schema = ?)
                  + (SELECT COUNT(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname = ? AND p.prokind IN ('f', 'p'))
                  + (SELECT COUNT(DISTINCT trigger_name) FROM information_schema.triggers WHERE trigger_schema = ?)
                AS cnt
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        Long cnt = runner.queryOne(sql, List.of(schema, schema, schema, schema, schema), rs -> rs.getLong(1));
        return cnt == null ? 0 : cnt;
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws Exception {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws Exception {
        String schema = databaseName != null && !databaseName.isBlank() ? databaseName : currentSchema(conn);
        StringBuilder ddl = new StringBuilder();
        long completed = 0;

        ddl.append("-- ============================================================\n");
        ddl.append("-- PostgreSQL Schema DDL Export: ").append(schema).append("\n");
        ddl.append("-- ============================================================\n\n");

        ddl.append("CREATE SCHEMA IF NOT EXISTS ").append(quoteIdentifier(schema)).append(";\n\n");

        // Tables
        List<String> tables = listSchemaObjects(conn, schema, "BASE TABLE");
        for (String table : tables) {
            ddl.append(printTable(conn, plainQualified(schema, table))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        // Views
        List<String> views = listSchemaObjects(conn, schema, "VIEW");
        for (String view : views) {
            ddl.append(printView(conn, plainQualified(schema, view))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        // Sequences
        List<String> sequences = listSequenceNames(conn, schema);
        for (String seq : sequences) {
            ddl.append(printSequence(conn, plainQualified(schema, seq))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        // Routines by oid so every overload is exported, not just an arbitrary one
        List<Long> routineOids = listRoutineOids(conn, schema);
        for (Long oid : routineOids) {
            ddl.append(printRoutineDefByOid(conn, oid)).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        // Triggers
        List<String> triggers = listTriggerNames(conn, schema);
        for (String trigger : triggers) {
            ddl.append(printTrigger(conn, plainQualified(schema, trigger))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }

        return ddl.toString();
    }

    @Override
    public String printTable(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String table = parseObjectName(objectName);
        StringBuilder ddl = new StringBuilder();

        // Columns: pg_attribute + format_type gives the exact type text
        // (varchar without length, plain numeric, arrays, domains all come out right)
        String colSql = """
                SELECT
                    a.attname AS column_name,
                    pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                    a.attnotnull AS not_null,
                    COALESCE(pg_catalog.pg_get_expr(d.adbin, d.adrelid), '') AS column_default
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND c.relkind IN ('r', 'p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> colDefs = new ArrayList<>();
        runner.query(colSql, List.of(schema, table), rs -> {
            StringBuilder colDef = new StringBuilder();
            colDef.append("    ").append(quoteIdentifier(rs.getString("column_name")));
            colDef.append(" ").append(rs.getString("data_type"));
            if (rs.getBoolean("not_null")) {
                colDef.append(" NOT NULL");
            }
            String defaults = rs.getString("column_default");
            if (defaults != null && !defaults.isBlank()) {
                colDef.append(" DEFAULT ").append(defaults);
            }
            colDefs.add(colDef.toString());
            return null;
        });

        ddl.append("CREATE TABLE ").append(qualifyName(schema, table)).append(" (\n");
        ddl.append(String.join(",\n", colDefs));

        // Primary key: real constraint name, columns in key order
        String pkSql = """
                SELECT con.conname, a.attname AS column_name
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
                WHERE con.contype = 'p'
                  AND n.nspname = ?
                  AND c.relname = ?
                ORDER BY array_position(con.conkey, a.attnum)
                """;

        String[] pkNameHolder = new String[1];
        List<String> pkCols = runner.query(pkSql, List.of(schema, table), rs -> {
            pkNameHolder[0] = rs.getString("conname");
            return rs.getString("column_name");
        });
        String pkName = pkNameHolder[0] != null ? pkNameHolder[0] : table + "_pkey";
        if (!pkCols.isEmpty()) {
            ddl.append(",\n    CONSTRAINT ").append(quoteIdentifier(pkName))
                    .append(" PRIMARY KEY (");
            ddl.append(pkCols.stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse(""));
            ddl.append(")");
        }

        ddl.append("\n);\n");

        // Table comment
        String commentSql = "SELECT COALESCE(pg_catalog.obj_description(c.oid), '') AS comment FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relname = ? AND n.nspname = ?";
        String comment = runner.queryOne(commentSql, List.of(table, schema), rs -> rs.getString("comment"));
        if (comment != null && !comment.isBlank()) {
            ddl.append("COMMENT ON TABLE ").append(qualifyName(schema, table)).append(" IS '")
                    .append(comment.replace("'", "''")).append("';\n");
        }

        return ddl.toString();
    }

    @Override
    public String printView(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String view = parseObjectName(objectName);

        String sql = """
                SELECT pg_catalog.pg_get_viewdef(c.oid, true) AS viewdef
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ? AND n.nspname = ?
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String viewDef = runner.queryOne(sql, List.of(view, schema), rs -> rs.getString("viewdef"));
        if (viewDef == null || viewDef.isBlank()) {
            return "-- View " + qualifyName(schema, view) + " not found";
        }
        return "CREATE OR REPLACE VIEW " + qualifyName(schema, view) + " AS\n" + viewDef + ";";
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String index = parseObjectName(objectName);

        String sql = """
                SELECT pg_catalog.pg_get_indexdef(i.indexrelid) AS indexdef
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ? AND n.nspname = ?
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String indexDef = runner.queryOne(sql, List.of(index, schema), rs -> rs.getString("indexdef"));
        if (indexDef == null || indexDef.isBlank()) {
            return "-- Index " + qualifyName(schema, index) + " not found";
        }
        return indexDef + ";";
    }

    @Override
    public String printSequence(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String sequence = parseObjectName(objectName);

        String sql = """
                SELECT
                    sequence_name,
                    data_type,
                    start_value::text AS start_value,
                    minimum_value::text AS min_value,
                    maximum_value::text AS max_value,
                    increment::text AS increment,
                    CASE WHEN cycle_option = 'YES' THEN 'CYCLE' ELSE 'NO CYCLE' END AS cycle_option,
                    COALESCE(ps.seqcache::text, '1') AS cache_size
                FROM information_schema.sequences s
                LEFT JOIN pg_catalog.pg_class c ON c.relname = s.sequence_name AND c.relkind = 'S'
                LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.sequence_schema
                LEFT JOIN pg_catalog.pg_sequence ps ON ps.seqrelid = c.oid
                WHERE s.sequence_schema = ? AND s.sequence_name = ?
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        return runner.queryOne(sql, List.of(schema, sequence), rs -> {
            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE SEQUENCE ").append(qualifyName(schema, rs.getString("sequence_name")));
            ddl.append("\n    START WITH ").append(rs.getString("start_value"));
            ddl.append("\n    INCREMENT BY ").append(rs.getString("increment"));
            ddl.append("\n    MINVALUE ").append(rs.getString("min_value"));
            ddl.append("\n    MAXVALUE ").append(rs.getString("max_value"));
            ddl.append("\n    CACHE ").append(rs.getString("cache_size"));
            ddl.append("\n    ").append(rs.getString("cycle_option"));
            ddl.append(";\n");
            return ddl.toString();
        });
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String func = parseObjectName(objectName);

        String sql = """
                SELECT pg_catalog.pg_get_functiondef(p.oid) AS funcdef
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE p.proname = ? AND n.nspname = ?
                ORDER BY p.oid
                LIMIT 1
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String funcDef = runner.queryOne(sql, List.of(func, schema), rs -> rs.getString("funcdef"));
        if (funcDef == null || funcDef.isBlank()) {
            return "-- Function " + qualifyName(schema, func) + " not found";
        }
        return funcDef + ";";
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws Exception {
        // In PostgreSQL, procedures are similar to functions; use the same approach
        return printFunction(conn, objectName);
    }

    @Override
    public String printSynonym(Connection conn, String objectName) {
        return "-- PostgreSQL does not support synonyms";
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws Exception {
        String schema = parseSchema(objectName, conn);
        String trigger = parseObjectName(objectName);

        // pg_get_triggerdef returns the complete CREATE TRIGGER statement,
        // correct for multi-event triggers and any action statement form
        String sql = """
                SELECT pg_catalog.pg_get_triggerdef(t.oid) AS def
                FROM pg_catalog.pg_trigger t
                JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal
                LIMIT 1
                """;

        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String def = runner.queryOne(sql, List.of(trigger, schema), rs -> rs.getString("def"));
        if (def == null || def.isBlank()) {
            return "-- Trigger " + qualifyName(schema, trigger) + " not found";
        }
        return def.endsWith(";") ? def : def + ";";
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return "-- PostgreSQL does not support packages";
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    protected String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (Exception ignored) {
        }
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String schema = runner.queryOne("SELECT current_schema()", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema : defaultSchemaName();
    }

    protected String parseSchema(String objectName, Connection conn) throws SQLException {
        if (objectName == null || !objectName.contains(".")) {
            return currentSchema(conn);
        }
        return objectName.substring(0, objectName.lastIndexOf('.'));
    }

    protected String parseObjectName(String objectName) {
        if (objectName == null) {
            return "";
        }
        int dot = objectName.lastIndexOf('.');
        return dot >= 0 ? objectName.substring(dot + 1) : objectName;
    }

    protected String qualifyName(String schema, String object) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(object);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(object);
    }

    /** Unquoted schema.object for the print* methods, which parse names back apart. */
    private static String plainQualified(String schema, String object) {
        return schema == null || schema.isBlank() ? object : schema + "." + object;
    }

    protected String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return "\"\"";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    protected long notifyProgress(LongConsumer callback, long completed) {
        if (callback != null) {
            callback.accept(completed + 1);
        }
        return completed + 1;
    }

    private List<String> listSchemaObjects(Connection conn, String schema, String tableType) throws SQLException {
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = ?
                ORDER BY table_name
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        return runner.query(sql, List.of(schema, tableType), rs -> rs.getString("table_name"));
    }

    private List<String> listSequenceNames(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT sequence_name
                FROM information_schema.sequences
                WHERE sequence_schema = ?
                ORDER BY sequence_name
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        return runner.query(sql, List.of(schema), rs -> rs.getString("sequence_name"));
    }

    private List<Long> listRoutineOids(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT p.oid
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
                ORDER BY p.proname, p.oid
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        return runner.query(sql, List.of(schema), rs -> rs.getLong(1));
    }

    private String printRoutineDefByOid(Connection conn, long oid) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String funcDef = runner.queryOne("SELECT pg_catalog.pg_get_functiondef(" + oid + ")", null,
                rs -> rs.getString(1));
        return funcDef == null || funcDef.isBlank() ? "" : funcDef + ";";
    }

    private List<String> listTriggerNames(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT DISTINCT trigger_name
                FROM information_schema.triggers
                WHERE trigger_schema = ?
                ORDER BY trigger_name
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        return runner.query(sql, List.of(schema), rs -> rs.getString("trigger_name"));
    }
}
