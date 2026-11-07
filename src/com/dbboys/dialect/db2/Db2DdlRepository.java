package com.dbboys.dialect.db2;

import com.dbboys.core.DdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Db2 DDL export built from SYSCAT catalog views.
 * Views/routines/triggers carry their original text in the catalog;
 * tables/indexes/sequences are reconstructed from column and constraint metadata.
 */
public final class Db2DdlRepository implements DdlRepository {

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final String SQL_VIEW_TEXT = """
            SELECT TEXT
            FROM SYSCAT.VIEWS
            WHERE VIEWSCHEMA = ?
              AND VIEWNAME = ?
            """;

    private static final String SQL_ROUTINE_TEXT = """
            SELECT TEXT
            FROM SYSCAT.ROUTINES
            WHERE ROUTINESCHEMA = ?
              AND ROUTINENAME = ?
              AND ROUTINETYPE = ?
            ORDER BY SPECIFICNAME
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String SQL_TRIGGER_TEXT = """
            SELECT TEXT
            FROM SYSCAT.TRIGGERS
            WHERE TRIGSCHEMA = ?
              AND TRIGNAME = ?
            """;

    private static final String SQL_SYNONYM_BASE = """
            SELECT BASETABSCHEMA, BASETABNAME
            FROM SYSCAT.SYNONYMS
            WHERE TABSCHEMA = ?
              AND SYNNAME = ?
            """;

    private static final String SQL_SEQUENCE_DETAIL = """
            SELECT MINVALUE, MAXVALUE, INCREMENT, START, CACHE, CYCLE, "ORDER"
            FROM SYSCAT.SEQUENCES
            WHERE SEQSCHEMA = ?
              AND SEQNAME = ?
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLNAME, TYPENAME, LENGTH, SCALE, NULLS, DEFAULT, IDENTITY, HIDDEN
            FROM SYSCAT.COLUMNS
            WHERE TABSCHEMA = ?
              AND TABNAME = ?
            ORDER BY COLNO
            """;

    private static final String SQL_PRIMARY_KEY = """
            SELECT c.CONSTNAME, k.COLNAME
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

    private static final String SQL_INDEXES_OF_TABLE = """
            SELECT i.INDSCHEMA, i.INDNAME, i.UNIQUERULE, i.INDEXTYPE
            FROM SYSCAT.INDEXES i
            WHERE i.TABSCHEMA = ?
              AND i.TABNAME = ?
            ORDER BY i.INDNAME
            """;

    private static final String SQL_INDEX_COLUMNS = """
            SELECT COLNAME, COLORDER
            FROM SYSCAT.INDEXCOLUSE
            WHERE INDSCHEMA = ?
              AND INDNAME = ?
            ORDER BY COLSEQ
            """;

    private static final String SQL_INDEX_TABLE = """
            SELECT TABSCHEMA, TABNAME, UNIQUERULE
            FROM SYSCAT.INDEXES
            WHERE INDSCHEMA = ?
              AND INDNAME = ?
            """;

    private static final String SQL_TABLE_NAMES = """
            SELECT TABNAME
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TYPE IN ('T', 'S', 'N', 'H', 'U')
            ORDER BY TABNAME
            """;

    private static final String SQL_VIEW_NAMES = """
            SELECT VIEWNAME
            FROM SYSCAT.VIEWS
            WHERE VIEWSCHEMA = ?
            ORDER BY VIEWNAME
            """;

    private static final String SQL_SEQUENCE_NAMES = """
            SELECT SEQNAME
            FROM SYSCAT.SEQUENCES
            WHERE SEQSCHEMA = ?
              AND SEQTYPE = 'S'
            ORDER BY SEQNAME
            """;

    private static final String SQL_ROUTINE_NAMES = """
            SELECT ROUTINENAME
            FROM SYSCAT.ROUTINES
            WHERE ROUTINESCHEMA = ?
              AND ROUTINETYPE = ?
            ORDER BY ROUTINENAME, SPECIFICNAME
            """;

    private static final String SQL_TRIGGER_NAMES = """
            SELECT TRIGNAME
            FROM SYSCAT.TRIGGERS
            WHERE TRIGSCHEMA = ?
            ORDER BY TRIGNAME
            """;

    // ==================================================================

    @Override
    public String printTable(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        String schema = parts[0];
        String table = parts[1];

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(qualified(schema, table)).append(" (\n");

        SqlRunner runner = runner(conn);
        List<String> columnDefs = runner.query(SQL_COLUMNS, List.of(schema, table), rs -> {
            StringBuilder col = new StringBuilder("  ");
            col.append(quote(rs.getString("COLNAME").trim())).append(' ');
            String typeName = rs.getString("TYPENAME").trim();
            col.append(formatType(typeName, rs.getInt("LENGTH"), rs.getInt("SCALE")));
            if ("Y".equalsIgnoreCase(rs.getString("IDENTITY"))) {
                col.append(" GENERATED BY DEFAULT AS IDENTITY");
            }
            String defaultValue = rs.getString("DEFAULT");
            if (defaultValue != null && !defaultValue.isBlank()) {
                col.append(" DEFAULT ").append(defaultValue.trim());
            }
            if (!"Y".equalsIgnoreCase(rs.getString("NULLS"))) {
                col.append(" NOT NULL");
            }
            if ("Y".equalsIgnoreCase(rs.getString("HIDDEN"))) {
                col.append(" IMPLICITLY HIDDEN");
            }
            return col.toString();
        });
        ddl.append(String.join(",\n", columnDefs));

        List<String[]> pk = runner.query(SQL_PRIMARY_KEY, List.of(schema, table),
                rs -> new String[]{rs.getString("CONSTNAME").trim(), rs.getString("COLNAME").trim()});
        if (!pk.isEmpty()) {
            String constraintName = pk.get(0)[0];
            List<String> cols = new ArrayList<>();
            for (String[] row : pk) {
                cols.add(quote(row[1]));
            }
            ddl.append(",\n  CONSTRAINT ").append(quote(constraintName))
                    .append(" PRIMARY KEY (").append(String.join(", ", cols)).append(")");
        }
        ddl.append("\n);");
        return ddl.toString();
    }

    @Override
    public String printTableWithDependencies(Connection conn, String objectName) throws Exception {
        StringBuilder ddl = new StringBuilder(printTable(conn, objectName));
        String[] parts = splitQualified(objectName, currentSchema(conn));
        SqlRunner runner = runner(conn);
        List<String[]> indexes = runner.query(SQL_INDEXES_OF_TABLE, List.of(parts[0], parts[1]),
                rs -> new String[]{
                        rs.getString("INDSCHEMA").trim(),
                        rs.getString("INDNAME").trim(),
                        rs.getString("UNIQUERULE").trim()});
        for (String[] index : indexes) {
            if ("P".equals(index[2])) {
                continue; // primary key index already covered by the constraint clause
            }
            ddl.append("\n\n").append(buildIndexDdl(conn, index[0], index[1], parts[0], parts[1], "U".equals(index[2])));
        }
        return ddl.toString();
    }

    @Override
    public String printView(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        String text = runner(conn).queryOne(SQL_VIEW_TEXT, List.of(parts[0], parts[1]), rs -> rs.getString(1));
        return text == null ? "-- view " + objectName : text.strip();
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        String[] info = runner(conn).queryOne(SQL_INDEX_TABLE, List.of(parts[0], parts[1]),
                rs -> new String[]{rs.getString("TABSCHEMA").trim(), rs.getString("TABNAME").trim(),
                        rs.getString("UNIQUERULE").trim()});
        if (info == null) {
            return "-- index " + objectName;
        }
        return buildIndexDdl(conn, parts[0], parts[1], info[0], info[1], "U".equals(info[2]));
    }

    private String buildIndexDdl(Connection conn, String indSchema, String indName,
                                 String tabSchema, String tabName, boolean unique) throws SQLException {
        List<String> cols = runner(conn).query(SQL_INDEX_COLUMNS, List.of(indSchema, indName), rs -> {
            String name = quote(rs.getString("COLNAME").trim());
            String order = rs.getString("COLORDER");
            if ("D".equals(order)) {
                return name + " DESC";
            }
            if ("R".equals(order)) {
                return name + " RANDOM";
            }
            return name;
        });
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + qualified(indSchema, indName)
                + " ON " + qualified(tabSchema, tabName) + " (" + String.join(", ", cols) + ");";
    }

    @Override
    public String printSequence(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        return runner(conn).queryOne(SQL_SEQUENCE_DETAIL, List.of(parts[0], parts[1]), rs -> {
            StringBuilder ddl = new StringBuilder("CREATE SEQUENCE ").append(qualified(parts[0], parts[1]));
            ddl.append("\n  START WITH ").append(rs.getLong("START"));
            ddl.append("\n  INCREMENT BY ").append(rs.getLong("INCREMENT"));
            ddl.append("\n  MINVALUE ").append(rs.getLong("MINVALUE"));
            ddl.append("\n  MAXVALUE ").append(rs.getLong("MAXVALUE"));
            ddl.append("\n  ").append("Y".equalsIgnoreCase(rs.getString("CYCLE")) ? "CYCLE" : "NO CYCLE");
            ddl.append("\n  CACHE ").append(rs.getLong("CACHE"));
            ddl.append("\n  ").append("Y".equalsIgnoreCase(rs.getString("ORDER")) ? "ORDER" : "NO ORDER");
            return ddl.append(";").toString();
        });
    }

    @Override
    public String printSynonym(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        return runner(conn).queryOne(SQL_SYNONYM_BASE, List.of(parts[0], parts[1]), rs -> {
            String base = qualified(rs.getString("BASETABSCHEMA").trim(), rs.getString("BASETABNAME").trim());
            return "CREATE SYNONYM " + qualified(parts[0], parts[1]) + " FOR " + base + ";";
        });
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws Exception {
        return printRoutine(conn, objectName, "F", "function");
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws Exception {
        return printRoutine(conn, objectName, "P", "procedure");
    }

    private String printRoutine(Connection conn, String objectName, String routineType, String label) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        String text = runner(conn).queryOne(SQL_ROUTINE_TEXT, List.of(parts[0], parts[1], routineType), rs -> rs.getString(1));
        return text == null ? "-- " + label + " " + objectName : text.strip();
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws Exception {
        String[] parts = splitQualified(objectName, currentSchema(conn));
        String text = runner(conn).queryOne(SQL_TRIGGER_TEXT, List.of(parts[0], parts[1]), rs -> rs.getString(1));
        return text == null ? "-- trigger " + objectName : text.strip();
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return "--";
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws Exception {
        String schema = databaseName == null ? "" : databaseName.trim();
        StringBuilder ddl = new StringBuilder();
        SqlRunner runner = runner(conn);

        List<String> sequences = runner.query(SQL_SEQUENCE_NAMES, List.of(schema), rs -> rs.getString(1).trim());
        for (String name : sequences) {
            appendObject(ddl, printSequence(conn, qualified(schema, name)));
        }
        List<String> tables = runner.query(SQL_TABLE_NAMES, List.of(schema), rs -> rs.getString(1).trim());
        for (String name : tables) {
            appendObject(ddl, printTableWithDependencies(conn, qualified(schema, name)));
        }
        List<String> views = runner.query(SQL_VIEW_NAMES, List.of(schema), rs -> rs.getString(1).trim());
        for (String name : views) {
            appendObject(ddl, printView(conn, qualified(schema, name)));
        }
        List<String> functions = runner.query(SQL_ROUTINE_NAMES, List.of(schema, "F"), rs -> rs.getString(1).trim());
        for (String name : functions) {
            appendObject(ddl, printFunction(conn, qualified(schema, name)));
        }
        List<String> procedures = runner.query(SQL_ROUTINE_NAMES, List.of(schema, "P"), rs -> rs.getString(1).trim());
        for (String name : procedures) {
            appendObject(ddl, printProcedure(conn, qualified(schema, name)));
        }
        List<String> triggers = runner.query(SQL_TRIGGER_NAMES, List.of(schema), rs -> rs.getString(1).trim());
        for (String name : triggers) {
            appendObject(ddl, printTrigger(conn, qualified(schema, name)));
        }
        return ddl.toString();
    }

    private static void appendObject(StringBuilder ddl, String objectDdl) {
        if (objectDdl == null || objectDdl.isBlank()) {
            return;
        }
        if (ddl.length() > 0) {
            ddl.append("\n\n");
        }
        ddl.append(objectDdl);
        if (!objectDdl.stripTrailing().endsWith(";")) {
            ddl.append(";");
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    static String formatType(String typeName, int length, int scale) {
        String type = typeName == null ? "" : typeName.trim();
        return switch (type) {
            case "CHARACTER", "CHAR" -> "CHAR(" + length + ")";
            case "VARCHAR" -> "VARCHAR(" + length + ")";
            case "LONG VARCHAR" -> "LONG VARCHAR";
            case "GRAPHIC" -> "GRAPHIC(" + length + ")";
            case "VARGRAPHIC" -> "VARGRAPHIC(" + length + ")";
            case "DECIMAL", "NUMERIC" -> "DECIMAL(" + length + ", " + scale + ")";
            case "DECFLOAT" -> "DECFLOAT(" + length + ")";
            case "BINARY" -> "BINARY(" + length + ")";
            case "VARBINARY" -> "VARBINARY(" + length + ")";
            default -> type;
        };
    }

    static String qualified(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quote(name);
        }
        return quote(schema) + "." + quote(name);
    }

    static String quote(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String[] splitQualified(String name, String defaultSchema) {
        String trimmed = name == null ? "" : name.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot > 0) {
            return new String[]{trimmed.substring(0, dot).replace("\"", ""), trimmed.substring(dot + 1).replace("\"", "")};
        }
        return new String[]{defaultSchema == null ? "" : defaultSchema.trim(), trimmed.replace("\"", "")};
    }

    private static String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        String schema = runner(conn).queryOne("SELECT CURRENT SCHEMA FROM SYSIBM.SYSDUMMY1", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema.trim() : "";
    }

    private static SqlRunner runner(Connection conn) {
        return new SqlRunner(conn, QUERY_TIMEOUT_SECONDS);
    }
}
