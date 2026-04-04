package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.DdlRepository;
import com.dbboys.db.SqlRunner;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OracleDdlRepository implements DdlRepository {

    private static final Logger log = LogManager.getLogger(OracleDdlRepository.class);
    private static final int QUERY_TIMEOUT = 60;

    private static final String SQL_OBJECT_COUNT = """
            SELECT COUNT(*) FROM all_objects
            WHERE owner = ? AND object_type IN (
                'TABLE','VIEW','INDEX','SEQUENCE','SYNONYM',
                'FUNCTION','PROCEDURE','TRIGGER','PACKAGE'
            ) AND secondary = 'N'
            """;

    private static final String SQL_SCHEMA_OBJECTS = """
            SELECT object_name, object_type FROM all_objects
            WHERE owner = ? AND object_type = ? AND secondary = 'N'
            ORDER BY object_name
            """;

    private static final String SQL_COMMENTS = """
            SELECT table_name, column_name, comments FROM all_col_comments
            WHERE owner = ? AND comments IS NOT NULL
            ORDER BY table_name, column_name
            """;

    private static final String SQL_TABLE_COMMENTS = """
            SELECT table_name, comments FROM all_tab_comments
            WHERE owner = ? AND comments IS NOT NULL AND table_type = 'TABLE'
            ORDER BY table_name
            """;

    private String currentSchema(Connection conn) throws SQLException {
        String schema = conn.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema.toUpperCase();
        }
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String result = runner.queryOne(
                "SELECT sys_context('USERENV','CURRENT_SCHEMA') FROM dual",
                null, rs -> rs.getString(1));
        return result != null ? result.toUpperCase() : "ORACLE";
    }

    private String getDdl(Connection conn, String objectType, String objectName, String schema) throws SQLException {
        String sql = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual";
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String ddl = runner.queryOne(sql, List.of(objectType, objectName, schema), rs -> {
            Object obj = rs.getObject(1);
            if (obj instanceof Clob clob) {
                try {
                    return clob.getSubString(1, (int) clob.length());
                } finally {
                    clob.free();
                }
            }
            return rs.getString(1);
        });
        return ddl != null ? ddl.trim() : "";
    }

    private String getDdlSafe(Connection conn, String objectType, String objectName, String schema) {
        try {
            return getDdl(conn, objectType, objectName, schema);
        } catch (Exception e) {
            log.warn("Failed to get DDL for {} {}.{}: {}", objectType, schema, objectName, e.getMessage());
            return "-- ERROR: Failed to get DDL for " + objectType + " " + schema + "." + objectName
                    + "\n-- " + e.getMessage();
        }
    }

    private void configureMetadataTransform(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'TABLESPACE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE);" +
                    "END;");
        }
    }

    @Override
    public String printTable(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return getDdl(conn, "TABLE", objectName.toUpperCase(), schema);
    }

    @Override
    public String printView(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "VIEW", objectName.toUpperCase(), schema);
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return getDdl(conn, "INDEX", objectName.toUpperCase(), schema);
    }

    @Override
    public String printSequence(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "SEQUENCE", objectName.toUpperCase(), schema);
    }

    @Override
    public String printSynonym(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "SYNONYM", objectName.toUpperCase(), schema);
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "FUNCTION", objectName.toUpperCase(), schema);
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "PROCEDURE", objectName.toUpperCase(), schema);
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "TRIGGER", objectName.toUpperCase(), schema);
    }

    @Override
    public String printPackage(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        StringBuilder sb = new StringBuilder();
        sb.append(getDdl(conn, "PACKAGE_SPEC", objectName.toUpperCase(), schema));
        try {
            String body = getDdl(conn, "PACKAGE_BODY", objectName.toUpperCase(), schema);
            if (!body.isEmpty()) {
                sb.append("\n/\n\n");
                sb.append(body);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    @Override
    public long countDatabaseExportItems(Connection conn, String databaseName) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn);
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        Integer count = runner.queryOne(SQL_OBJECT_COUNT, List.of(schema), rs -> rs.getInt(1));
        return count != null ? count : 0;
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws SQLException {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn);
        configureMetadataTransform(conn);

        StringBuilder ddl = new StringBuilder();
        long completed = 0;

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        ddl.append("-- ############################################################\n");
        ddl.append("-- ### Oracle Schema DDL Export\n");
        ddl.append("-- ### Schema   : ").append(schema).append("\n");
        ddl.append("-- ### Datetime : ").append(dateStr).append("\n");
        ddl.append("-- ############################################################\n\n");

        completed = appendObjectsDdl(conn, ddl, schema, "SEQUENCE", "Sequences", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "TABLE", "Tables", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "INDEX", "Indexes", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "VIEW", "Views", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "SYNONYM", "Synonyms", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "FUNCTION", "Functions", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "PROCEDURE", "Procedures", completed, progressCallback);
        completed = appendPackagesDdl(conn, ddl, schema, completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "TRIGGER", "Triggers", completed, progressCallback);

        appendTableComments(conn, ddl, schema);
        appendColumnComments(conn, ddl, schema);

        ddl.append("-- ### END OF EXPORT\n");
        return ddl.toString();
    }

    private long appendObjectsDdl(Connection conn, StringBuilder ddl, String schema,
                                  String objectType, String label,
                                  long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEMA_OBJECTS, List.of(schema, objectType),
                rs -> rs.getString("object_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### ").append(label).append(" (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, objectType, name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: ").append(label).append("\n\n");
        return completed;
    }

    private long appendPackagesDdl(Connection conn, StringBuilder ddl, String schema,
                                   long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEMA_OBJECTS, List.of(schema, "PACKAGE"),
                rs -> rs.getString("object_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### Packages (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String specDdl = getDdlSafe(conn, "PACKAGE_SPEC", name, schema);
            if (!specDdl.isEmpty()) {
                ddl.append(specDdl);
                if (!specDdl.endsWith("/")) {
                    ddl.append("\n/");
                }
                ddl.append("\n\n");
            }
            String bodyDdl = getDdlSafe(conn, "PACKAGE_BODY", name, schema);
            if (!bodyDdl.isEmpty() && !bodyDdl.startsWith("-- ERROR")) {
                ddl.append(bodyDdl);
                if (!bodyDdl.endsWith("/")) {
                    ddl.append("\n/");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Packages\n\n");
        return completed;
    }

    private void appendTableComments(Connection conn, StringBuilder ddl, String schema) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> comments = runner.query(SQL_TABLE_COMMENTS, List.of(schema),
                rs -> new String[]{rs.getString("table_name"), rs.getString("comments")});
        if (comments.isEmpty()) {
            return;
        }
        ddl.append("-- ### Table Comments\n\n");
        for (String[] row : comments) {
            String escapedComment = row[1].replace("'", "''");
            ddl.append("COMMENT ON TABLE \"").append(schema).append("\".\"").append(row[0])
                    .append("\" IS '").append(escapedComment).append("';\n");
        }
        ddl.append("\n");
    }

    private void appendColumnComments(Connection conn, StringBuilder ddl, String schema) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> comments = runner.query(SQL_COMMENTS, List.of(schema),
                rs -> new String[]{rs.getString("table_name"), rs.getString("column_name"), rs.getString("comments")});
        if (comments.isEmpty()) {
            return;
        }
        ddl.append("-- ### Column Comments\n\n");
        for (String[] row : comments) {
            String escapedComment = row[2].replace("'", "''");
            ddl.append("COMMENT ON COLUMN \"").append(schema).append("\".\"").append(row[0])
                    .append("\".\"").append(row[1]).append("\" IS '").append(escapedComment).append("';\n");
        }
        ddl.append("\n");
    }
}
